package com.flicklog.post.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flicklog.common.util.ObjectIdValidator;
import com.flicklog.common.util.SlugParser;
import com.flicklog.common.util.TagParser;
import com.flicklog.post.dto.request.PostRequest;
import com.flicklog.post.dto.response.PostsPageResponse;
import com.flicklog.common.exception.ApiException;
import com.flicklog.post.model.Comment;
import com.flicklog.post.model.ImageData;
import com.flicklog.post.model.Post;
import com.flicklog.common.cache.RedisCacheService;
import com.flicklog.user.model.User;
import com.flicklog.post.repository.PostRepository;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.Arrays;
import java.util.List;

/**
 * Mirrors controllers/post.controllers.js.
 */
@Slf4j
@Service
public class PostService {

    private static final int LIMIT = 6;

    private final PostRepository postRepository;
    private final RedisCacheService redisCacheService;
    private final CloudinaryService cloudinaryService;
    private final ObjectMapper objectMapper;
    private final MongoTemplate mongoTemplate;

    public PostService(PostRepository postRepository, RedisCacheService redisCacheService,
                       CloudinaryService cloudinaryService, ObjectMapper objectMapper,
                       MongoTemplate mongoTemplate) {
        this.postRepository = postRepository;
        this.redisCacheService = redisCacheService;
        this.cloudinaryService = cloudinaryService;
        this.objectMapper = objectMapper;
        this.mongoTemplate = mongoTemplate;
    }

    public Post fetchPost(String slugId) {
        // Mirrors slugId.split(/-(?=[^ -]+$)/) - split on the LAST hyphen only
        String id = SlugParser.extractId(slugId);

        ObjectIdValidator.requireValid(id, "Invalid post ID");

        String cacheKey = "post:" + slugId;
        String cached = redisCacheService.get(cacheKey);
        if (cached != null) {
            Post cachedPost = readCachedPost(cached);
            if (cachedPost != null) {
                return cachedPost;
            }
        }

        Post post = postRepository.findById(id)
                .orElseThrow(() -> {
                    log.warn("Post not found for id {}", id);
                    return new ApiException("Post not found", 404);
                });

        cachePost(cacheKey, post);
        return post;
    }

    public PostsPageResponse fetchPosts(int pageNumber) {
        String cacheKey = "posts:page:" + pageNumber;
        String cached = redisCacheService.get(cacheKey);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, PostsPageResponse.class);
            } catch (Exception e) {
                log.debug("Cache read/parse failed for key {}, falling back to DB: {}", cacheKey, e.getMessage());
            }
        }

        int startIndex = (pageNumber - 1) * LIMIT;
        long total = postRepository.count();
        List<Post> posts = postRepository.findAllByOrderByIdDesc(
                PageRequest.of(startIndex / LIMIT, LIMIT, Sort.by(Sort.Direction.DESC, "id")));

        PostsPageResponse response = new PostsPageResponse(
                posts, pageNumber, (int) Math.ceil((double) total / LIMIT));

        try {
            redisCacheService.set(cacheKey, objectMapper.writeValueAsString(response));
        } catch (Exception e) {
            log.debug("Failed to cache key {}: {}", cacheKey, e.getMessage());
        }

        return response;
    }

    public List<Post> fetchPostsBySearch(String searchQuery, String tags) {
        String cacheKey = "posts:search:" + (searchQuery == null ? "" : searchQuery) + ":tags:" + (tags == null ? "" : tags);
        String cached = redisCacheService.get(cacheKey);
        if (cached != null) {
            try {
                return List.of(objectMapper.readValue(cached, Post[].class));
            } catch (Exception e) {
                log.debug("Cache read/parse failed for key {}, falling back to DB: {}", cacheKey, e.getMessage());
            }
        }

        List<String> tagsArray = TagParser.parse(tags);
        List<Post> posts = postRepository.searchByTitleOrTags(searchQuery == null ? "" : searchQuery, tagsArray);

        try {
            redisCacheService.set(cacheKey, objectMapper.writeValueAsString(posts));
        } catch (Exception e) {
            log.debug("Failed to cache key {}: {}", cacheKey, e.getMessage());
        }

        return posts;
    }

    public Post createPost(PostRequest request, MultipartFile file, String creatorId) {
        Post post = new Post();
        post.setTitle(request.getTitle());
        post.setMessage(request.getMessage());
        post.setName(request.getName());
        post.setCreator(creatorId);
        post.setTags(TagParser.parse(request.getTags()));
        post.setSlug(request.getSlug());
        post.setCreatedAt(Instant.now());

        if (file != null && !file.isEmpty()) {
            ImageData imageData = uploadImage(file);
            post.setImage(imageData);
            post.setSelectedfile(imageData.getUrl());
        } else {
            post.setSelectedfile("");
        }

        Post saved = postRepository.save(post);
        invalidatePostCaches();
        log.info("Post {} created by user {}", saved.getId(), creatorId);
        return saved;
    }

    public Post updatePost(String id, PostRequest request, MultipartFile file, String requesterId) {
        ObjectIdValidator.requireValid(id, "Invalid post ID");

        if (requesterId == null) {
            throw new ApiException("Unauthorized action", 401);
        }

        Post existing = postRepository.findById(id)
                .orElseThrow(() -> {
                    log.warn("Update failed: post not found for id {}", id);
                    return new ApiException("Post not found", 404);
                });

        if (existing.getCreator() == null || !existing.getCreator().equals(requesterId)) {
            log.warn("Update rejected: requester {} attempted to update post {} owned by {}",
                    requesterId, id, existing.getCreator());
            throw new ApiException("You can only update your own posts", 403);
        }

        existing.setTitle(request.getTitle());
        existing.setMessage(request.getMessage());
        existing.setTags(TagParser.parse(request.getTags()));
        existing.setSlug(request.getSlug());

        if (file != null && !file.isEmpty()) {
            if (existing.getImage() != null && existing.getImage().getPublicId() != null) {
                cloudinaryService.destroy(existing.getImage().getPublicId());
            }
            ImageData imageData = uploadImage(file);
            existing.setImage(imageData);
            existing.setSelectedfile(imageData.getUrl());
        }

        Post updated = postRepository.save(existing);

        invalidatePostCaches();
        log.info("Post {} updated by {}", id, requesterId);

        return updated;
    }

    public void deletePost(String id, String requesterId) {
        ObjectIdValidator.requireValid(id, "Invalid post ID");

        if (requesterId == null) {
            throw new ApiException("Unauthorized action", 401);
        }

        Post post = postRepository.findById(id)
                .orElseThrow(() -> {
                    log.warn("Delete failed: post not found for id {}", id);
                    return new ApiException("Post not found", 404);
                });

        if (post.getCreator() == null || !post.getCreator().equals(requesterId)) {
            log.warn("Delete rejected: requester {} attempted to delete post {} owned by {}",
                    requesterId, id, post.getCreator());
            throw new ApiException("You can only delete your own posts", 403);
        }

        if (post.getImage() != null && post.getImage().getPublicId() != null) {
            cloudinaryService.destroy(post.getImage().getPublicId());
        }

        postRepository.deleteById(id);

        invalidatePostCaches();
        log.info("Post {} deleted by {}", id, requesterId);
    }

    public Post likePost(String id, String userId) {
        if (userId == null) {
            throw new ApiException("Unauthenticated", 401);
        }
        ObjectIdValidator.requireValid(id, "Invalid post ID");

        // Try to atomically remove the like first. If the post currently has this
        // userId in `likes`, this matches-and-modifies in one operation - no read,
        // no separate save, so two concurrent likes/unlikes can never clobber
        // each other the way a read-then-save-whole-document approach can.
        Query pullQuery = Query.query(Criteria.where("id").is(id).and("likes").is(userId));
        Update pullUpdate = new Update().pull("likes", userId);
        Post result = mongoTemplate.findAndModify(
                pullQuery, pullUpdate, FindAndModifyOptions.options().returnNew(true), Post.class);

        if (result != null) {
            log.info("User {} unliked post {}", userId, id);
            invalidatePostCaches();
            return result;
        }

        // Wasn't liked (or didn't match) - atomically add instead. addToSet is
        // idempotent, and this query matches on id alone, so if the post exists
        // this always succeeds; if it returns null, the post genuinely doesn't exist.
        Query addQuery = Query.query(Criteria.where("id").is(id));
        Update addUpdate = new Update().addToSet("likes", userId);
        result = mongoTemplate.findAndModify(
                addQuery, addUpdate, FindAndModifyOptions.options().returnNew(true), Post.class);

        if (result == null) {
            log.warn("Like failed: post not found for id {}", id);
            throw new ApiException("Post not found", 404);
        }

        log.info("User {} liked post {}", userId, id);
        invalidatePostCaches();
        return result;
    }

    public Post commentPost(String id, String value, String authorId) {
        if (authorId == null) {
            throw new ApiException("Unauthenticated", 401);
        }
        if (value == null || value.isBlank()) {
            throw new ApiException("Comment cannot be empty", 400);
        }
        ObjectIdValidator.requireValid(id, "Invalid post ID");

        Comment comment = new Comment(
                new ObjectId().toHexString(), authorId, value, Instant.now());

        Query query = Query.query(Criteria.where("id").is(id));
        Update update = new Update().push("comments", comment);
        Post result = mongoTemplate.findAndModify(
                query, update, FindAndModifyOptions.options().returnNew(true), Post.class);

        if (result == null) {
            log.warn("Comment failed: post not found for id {}", id);
            throw new ApiException("Post not found", 404);
        }

        log.info("Comment added to post {} by {}", id, authorId);
        invalidatePostCaches();
        return result;
    }

    public List<String> bookmarkPost(String postId, String userId) {
        if (!mongoTemplate.exists(Query.query(Criteria.where("id").is(userId)), User.class)) {
            log.warn("Bookmark failed: user not found for id {}", userId);
            throw new ApiException("User not found", 404);
        }

        Query pullQuery = Query.query(Criteria.where("id").is(userId).and("bookmarks").is(postId));
        Update pullUpdate = new Update().pull("bookmarks", postId);
        User result = mongoTemplate.findAndModify(
                pullQuery, pullUpdate, FindAndModifyOptions.options().returnNew(true), User.class);

        if (result != null) {
            redisCacheService.delete("user:" + userId);
            return result.getBookmarks();
        }

        Query addQuery = Query.query(Criteria.where("id").is(userId));
        Update addUpdate = new Update().addToSet("bookmarks", postId);
        result = mongoTemplate.findAndModify(
                addQuery, addUpdate, FindAndModifyOptions.options().returnNew(true), User.class);

        if (result == null) {
            throw new ApiException("User not found", 404);
        }
        redisCacheService.delete("user:" + userId);
        return result.getBookmarks();
    }

    private ImageData uploadImage(MultipartFile file) {
        try {
            return cloudinaryService.upload(file);
        } catch (Exception e) {
            log.error("Cloudinary upload failed", e);
            throw new ApiException("Image upload failed: " + e.getMessage(), 500);
        }
    }

    private void cachePost(String cacheKey, Post post) {
        try {
            redisCacheService.set(cacheKey, objectMapper.writeValueAsString(post));
        } catch (Exception e) {
            log.debug("Failed to cache key {}: {}", cacheKey, e.getMessage());
        }
    }

    private Post readCachedPost(String cached) {
        try {
            return objectMapper.readValue(cached, Post.class);
        } catch (Exception e) {
            return null;
        }
    }

    private void invalidatePostCaches() {
        redisCacheService.deleteByPattern("post:*");
        redisCacheService.deleteByPattern("posts:*");
    }
}
