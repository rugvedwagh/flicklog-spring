package com.flicklog.post.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flicklog.common.util.ObjectIdValidator;
import com.flicklog.common.util.SlugParser;
import com.flicklog.common.util.TagParser;
import com.flicklog.post.dto.request.PostRequest;
import com.flicklog.post.dto.response.PostsPageResponse;
import com.flicklog.common.exception.ApiException;
import com.flicklog.post.model.ImageData;
import com.flicklog.post.model.Post;
import com.flicklog.common.cache.RedisCacheService;
import com.flicklog.user.model.User;
import com.flicklog.post.repository.PostRepository;
import com.flicklog.user.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
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
    private final UserRepository userRepository;
    private final RedisCacheService redisCacheService;
    private final CloudinaryService cloudinaryService;
    private final ObjectMapper objectMapper;

    public PostService(PostRepository postRepository, UserRepository userRepository,
                       RedisCacheService redisCacheService, CloudinaryService cloudinaryService,
                       ObjectMapper objectMapper) {
        this.postRepository = postRepository;
        this.userRepository = userRepository;
        this.redisCacheService = redisCacheService;
        this.cloudinaryService = cloudinaryService;
        this.objectMapper = objectMapper;
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

        if (posts.isEmpty()) {
            throw new ApiException("No posts found", 404);
        }

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

        if (posts.isEmpty()) {
            throw new ApiException(
                    "No posts found with tags): [" + String.join(", ", tagsArray) + "] or title matching: " + searchQuery,
                    404);
        }

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
        redisCacheService.deleteByPattern("posts:*");
        log.info("Post {} created by user {}", saved.getId(), creatorId);
        return saved;
    }

    public Post updatePost(String id, PostRequest request, MultipartFile file) {
        ObjectIdValidator.requireValid(id, "Invalid post ID");

        Post existing = postRepository.findById(id)
                .orElseThrow(() -> {
                    log.warn("Update failed: post not found for id {}", id);
                    return new ApiException("Post not found", 404);
                });

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

        redisCacheService.delete("post:" + id);
        redisCacheService.deleteByPattern("posts:*");
        log.info("Post {} updated", id);

        return updated;
    }

    public void deletePost(String id) {
        ObjectIdValidator.requireValid(id, "Invalid post ID");

        Post post = postRepository.findById(id)
                .orElseThrow(() -> {
                    log.warn("Delete failed: post not found for id {}", id);
                    return new ApiException("Post not found", 404);
                });

        if (post.getImage() != null && post.getImage().getPublicId() != null) {
            cloudinaryService.destroy(post.getImage().getPublicId());
        }

        postRepository.deleteById(id);

        redisCacheService.delete("post:" + id);
        redisCacheService.deleteByPattern("posts:*");
        log.info("Post {} deleted", id);
    }

    public Post likePost(String id, String userId) {
        if (userId == null) {
            throw new ApiException("Unauthenticated", 401);
        }

        ObjectIdValidator.requireValid(id, "Invalid post ID");

        Post post = postRepository.findById(id)
                .orElseThrow(() -> {
                    log.warn("Like failed: post not found for id {}", id);
                    return new ApiException("Post not found", 404);
                });

        boolean nowLiked;
        if (post.getLikes().contains(userId)) {
            post.setLikes(new java.util.ArrayList<>(post.getLikes().stream().filter(u -> !u.equals(userId)).toList()));
            nowLiked = false;
        } else {
            post.getLikes().add(userId);
            nowLiked = true;
        }

        log.info("User {} {} post {}", userId, nowLiked ? "liked" : "unliked", id);
        return postRepository.save(post);
    }

    public Post commentPost(String id, String value) {
        if (value == null || value.isBlank()) {
            throw new ApiException("Comment cannot be empty", 400);
        }

        ObjectIdValidator.requireValid(id, "Invalid post ID");

        Post post = postRepository.findById(id)
                .orElseThrow(() -> {
                    log.warn("Comment failed: post not found for id {}", id);
                    return new ApiException("Post not found", 404);
                });

        post.getComments().add(value);
        log.info("Comment added to post {}", id);

        redisCacheService.delete("post:" + id);

        return postRepository.save(post);
    }

    public List<String> bookmarkPost(String postId, String userId) {
        User user = userRepository.findById(userId)
                .orElseThrow(() -> {
                    log.warn("Bookmark failed: user not found for id {}", userId);
                    return new ApiException("User not found", 404);
                });

        if (user.getBookmarks().contains(postId)) {
            user.setBookmarks(new java.util.ArrayList<>(user.getBookmarks().stream().filter(id -> !id.equals(postId)).toList()));
        } else {
            user.getBookmarks().add(postId);
        }

        userRepository.save(user);
        return user.getBookmarks();
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
}
