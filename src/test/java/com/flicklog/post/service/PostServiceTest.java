// src/test/java/com/flicklog/service/PostServiceTest.java
package com.flicklog.post.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flicklog.common.cache.RedisCacheService;
import com.flicklog.post.dto.request.PostRequest;
import com.flicklog.common.exception.ApiException;
import com.flicklog.post.model.Comment;
import com.flicklog.post.model.Post;
import com.flicklog.post.repository.PostRepository;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import com.flicklog.post.model.ImageData;
import com.flicklog.user.model.User;
import com.flicklog.post.dto.response.PostsPageResponse;
import org.springframework.data.domain.Pageable;


import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PostServiceTest {

    @Mock private PostRepository postRepository;
    @Mock private RedisCacheService redisCacheService;
    @Mock private CloudinaryService cloudinaryService;
    @Mock private ObjectMapper objectMapper;
    @Mock private MongoTemplate mongoTemplate;   // replaces UserRepository

    @InjectMocks private PostService postService;

    private Post existingPost;
    private String postId;
    private String ownerId;

    @BeforeEach
    void setUp() {
        postId = new ObjectId().toHexString();
        ownerId = new ObjectId().toHexString();

        existingPost = new Post();
        existingPost.setId(postId);
        existingPost.setTitle("Original title");
        existingPost.setCreator(ownerId);
        existingPost.setLikes(new java.util.ArrayList<>());
        existingPost.setComments(new java.util.ArrayList<>());
    }

    @Test
    void fetchPost_withInvalidId_throws400() {
        assertThatThrownBy(() -> postService.fetchPost("some-slug-not-an-objectid"))
                .isInstanceOf(ApiException.class)
                .hasMessage("Invalid post ID");
    }

    @Test
    void fetchPost_cacheMiss_fallsThroughToRepository() {
        when(redisCacheService.get("post:my-title-" + postId)).thenReturn(null);
        when(postRepository.findById(postId)).thenReturn(Optional.of(existingPost));

        Post result = postService.fetchPost("my-title-" + postId);

        assertThat(result.getTitle()).isEqualTo("Original title");
        verify(postRepository).findById(postId);
    }

    @Test
    void fetchPost_notFound_throws404() {
        when(redisCacheService.get(any())).thenReturn(null);
        when(postRepository.findById(postId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> postService.fetchPost("slug-" + postId))
                .isInstanceOf(ApiException.class)
                .hasMessage("Post not found");
    }

    @Test
    void likePost_addsUserIdWhenNotAlreadyLiked() {
        // pull attempt finds nothing to remove
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(Post.class)))
                .thenReturn(null)   // first call: pull attempt, not liked yet
                .thenReturn(existingPostWithLikes("user-123")); // second call: addToSet succeeds

        Post result = postService.likePost(postId, "user-123");

        assertThat(result.getLikes()).containsExactly("user-123");
    }

    @Test
    void likePost_removesUserIdWhenAlreadyLiked() {
        Post afterRemoval = existingPostWithLikes(); // empty likes after pull
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(Post.class)))
                .thenReturn(afterRemoval); // pull attempt succeeds immediately

        Post result = postService.likePost(postId, "user-123");

        assertThat(result.getLikes()).isEmpty();
        // only one Mongo call needed - the pull matched, so addToSet is never attempted
        verify(mongoTemplate, times(1)).findAndModify(any(), any(), any(), eq(Post.class));
    }

    @Test
    void likePost_withoutUserId_throws401() {
        assertThatThrownBy(() -> postService.likePost(postId, null))
                .isInstanceOf(ApiException.class)
                .hasMessage("Unauthenticated");

        verifyNoInteractions(mongoTemplate);
    }

    @Test
    void commentPost_withBlankValue_throws400() {
        assertThatThrownBy(() -> postService.commentPost(postId, "  ", ownerId))
                .isInstanceOf(ApiException.class)
                .hasMessage("Comment cannot be empty");
    }

    @Test
    void createPost_withoutFile_setsEmptySelectedFile() throws Exception {
        PostRequest request = new PostRequest();
        request.setTitle("New post");
        request.setMessage("Hello world");
        request.setTags("tag1, tag2");

        when(postRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Post result = postService.createPost(request, null, "creator-1");

        assertThat(result.getSelectedfile()).isEmpty();
        assertThat(result.getTags()).containsExactly("tag1", "tag2");
        verify(cloudinaryService, never()).upload(any());
        verify(redisCacheService).deleteByPattern("posts:*");
    }

    // --- fetchPosts ---

    @Test
    void fetchPosts_cacheHit_returnsCachedResponseWithoutHittingRepository() throws Exception {
        PostsPageResponse cachedResponse = new PostsPageResponse(List.of(existingPost), 1, 3);
        when(redisCacheService.get("posts:page:1")).thenReturn("{\"cached\":true}");
        when(objectMapper.readValue("{\"cached\":true}", PostsPageResponse.class)).thenReturn(cachedResponse);

        PostsPageResponse result = postService.fetchPosts(1);

        assertThat(result.getNumberOfPages()).isEqualTo(3);
        verifyNoInteractions(postRepository);
    }

    @Test
    void fetchPosts_cacheMiss_returnsPageFromRepository() throws Exception {
        when(redisCacheService.get("posts:page:1")).thenReturn(null);
        when(postRepository.count()).thenReturn(12L);
        when(postRepository.findAllByOrderByIdDesc(any(Pageable.class))).thenReturn(List.of(existingPost));
        when(objectMapper.writeValueAsString(any())).thenReturn("{}");

        PostsPageResponse result = postService.fetchPosts(1);

        assertThat(result.getData()).containsExactly(existingPost);
        assertThat(result.getCurrentPage()).isEqualTo(1);
        assertThat(result.getNumberOfPages()).isEqualTo(2); // ceil(12 / 6)
    }

    @Test
    void fetchPosts_emptyResults_returnsEmptyPageInsteadOfThrowing() {
        when(redisCacheService.get(any())).thenReturn(null);
        when(postRepository.count()).thenReturn(0L);
        when(postRepository.findAllByOrderByIdDesc(any(Pageable.class))).thenReturn(List.of());

        PostsPageResponse result = postService.fetchPosts(1);

        assertThat(result.getData()).isEmpty();
        assertThat(result.getCurrentPage()).isEqualTo(1);
        assertThat(result.getNumberOfPages()).isEqualTo(0);
    }

    @Test
    void likePost_postNotFound_throws404() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(Post.class)))
                .thenReturn(null); // both pull and addToSet return null - post doesn't exist

        assertThatThrownBy(() -> postService.likePost(postId, "user-123"))
                .isInstanceOf(ApiException.class)
                .hasMessage("Post not found");
    }

    // small helper for building the "post after mongo update" return value
    private Post existingPostWithLikes(String... likes) {
        Post post = new Post();
        post.setId(postId);
        post.setLikes(new java.util.ArrayList<>(java.util.List.of(likes)));
        return post;
    }

    // --- fetchPostsBySearch ---

    @Test
    void fetchPostsBySearch_withResults_returnsList() {
        when(redisCacheService.get(any())).thenReturn(null);
        when(postRepository.searchByTitleOrTags(eq("java"), any())).thenReturn(List.of(existingPost));

        List<Post> result = postService.fetchPostsBySearch("java", "tag1,tag2");

        assertThat(result).containsExactly(existingPost);
    }

    @Test
    void fetchPostsBySearch_noResults_returnsEmptyListInsteadOfThrowing() {
        when(redisCacheService.get(any())).thenReturn(null);
        when(postRepository.searchByTitleOrTags(any(), any())).thenReturn(List.of());

        List<Post> result = postService.fetchPostsBySearch("nothing", null);

        assertThat(result).isEmpty();
    }

    // --- updatePost ---

    @Test
    void updatePost_withInvalidId_throws400() {
        PostRequest request = new PostRequest();

        assertThatThrownBy(() -> postService.updatePost("not-an-id", request, null, ownerId))
                .isInstanceOf(ApiException.class)
                .hasMessage("Invalid post ID");
    }

    @Test
    void updatePost_nullRequester_throws401() {
        PostRequest request = new PostRequest();

        assertThatThrownBy(() -> postService.updatePost(postId, request, null, null))
                .isInstanceOf(ApiException.class)
                .hasMessage("Unauthorized action");

        verifyNoInteractions(postRepository);
    }

    @Test
    void updatePost_notFound_throws404() {
        when(postRepository.findById(postId)).thenReturn(Optional.empty());
        PostRequest request = new PostRequest();

        assertThatThrownBy(() -> postService.updatePost(postId, request, null, ownerId))
                .isInstanceOf(ApiException.class)
                .hasMessage("Post not found");
    }

    // Regression test for the IDOR bug: a logged-in user who isn't the post's
// creator must not be able to update someone else's post.
    @Test
    void updatePost_requesterNotOwner_throws403() {
        when(postRepository.findById(postId)).thenReturn(Optional.of(existingPost));

        PostRequest request = new PostRequest();
        request.setTitle("Hacked title");

        String someoneElseId = new ObjectId().toHexString();

        assertThatThrownBy(() -> postService.updatePost(postId, request, null, someoneElseId))
                .isInstanceOf(ApiException.class)
                .hasMessage("You can only update your own posts");

        verify(postRepository, never()).save(any());
    }

    @Test
    void updatePost_withoutFile_updatesFieldsAndClearsCache() {
        PostRequest request = new PostRequest();
        request.setTitle("Updated title");
        request.setMessage("Updated message");
        request.setTags("newtag");
        request.setSlug("updated-slug");

        when(postRepository.findById(postId)).thenReturn(Optional.of(existingPost));
        when(postRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Post result = postService.updatePost(postId, request, null, ownerId);

        assertThat(result.getTitle()).isEqualTo("Updated title");
        assertThat(result.getTags()).containsExactly("newtag");
        verify(cloudinaryService, never()).destroy(any());
        verify(redisCacheService).delete("post:" + postId);
        verify(redisCacheService).deleteByPattern("posts:*");
    }

    // --- deletePost ---

    @Test
    void deletePost_withInvalidId_throws400() {
        assertThatThrownBy(() -> postService.deletePost("not-an-id", ownerId))
                .isInstanceOf(ApiException.class)
                .hasMessage("Invalid post ID");
    }

    @Test
    void deletePost_notFound_throws404() {
        when(postRepository.findById(postId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> postService.deletePost(postId, ownerId))
                .isInstanceOf(ApiException.class)
                .hasMessage("Post not found");
    }

    @Test
    void deletePost_withImage_destroysCloudinaryImageAndDeletesFromRepo() {
        existingPost.setImage(new ImageData("http://cloud/img.png", "public-id-123", "img.png"));
        when(postRepository.findById(postId)).thenReturn(Optional.of(existingPost));

        postService.deletePost(postId, ownerId);

        verify(cloudinaryService).destroy("public-id-123");
        verify(postRepository).deleteById(postId);
        verify(redisCacheService).delete("post:" + postId);
        verify(redisCacheService).deleteByPattern("posts:*");
    }

    // Regression test for the IDOR bug: a logged-in user who isn't the post's
// creator must not be able to delete someone else's post.
    @Test
    void deletePost_requesterNotOwner_throws403() {
        when(postRepository.findById(postId)).thenReturn(Optional.of(existingPost));

        String someoneElseId = new ObjectId().toHexString();

        assertThatThrownBy(() -> postService.deletePost(postId, someoneElseId))
                .isInstanceOf(ApiException.class)
                .hasMessage("You can only delete your own posts");

        verify(postRepository, never()).deleteById(any());
        verify(cloudinaryService, never()).destroy(any());
    }

    @Test
    void deletePost_byOwner_deletesAndClearsCache() {
        existingPost.setImage(null); // no image to destroy in this case
        when(postRepository.findById(postId)).thenReturn(Optional.of(existingPost));

        postService.deletePost(postId, ownerId);

        verify(postRepository).deleteById(postId);
        verify(redisCacheService).delete("post:" + postId);
        verify(redisCacheService).deleteByPattern("posts:*");
    }

    // --- commentPost (happy path) ---

    @Test
    void commentPost_withValidValue_addsCommentWithAuthorAndClearsCache() {
        Post afterComment = new Post();
        afterComment.setId(postId);
        afterComment.setComments(new java.util.ArrayList<>(
                java.util.List.of(new Comment("comment-id-1", ownerId, "Great post!", java.time.Instant.now()))));

        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(Post.class)))
                .thenReturn(afterComment);

        Post result = postService.commentPost(postId, "Great post!", ownerId);

        assertThat(result.getComments()).hasSize(1);
        assertThat(result.getComments().get(0).getValue()).isEqualTo("Great post!");
        assertThat(result.getComments().get(0).getAuthorId()).isEqualTo(ownerId);
        verify(redisCacheService).delete("post:" + postId);
    }

    @Test
    void commentPost_withoutAuthorId_throws401() {
        assertThatThrownBy(() -> postService.commentPost(postId, "Great post!", null))
                .isInstanceOf(ApiException.class)
                .hasMessage("Unauthenticated");

        verifyNoInteractions(mongoTemplate);
    }

    @Test
    void commentPost_postNotFound_throws404() {
        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(Post.class)))
                .thenReturn(null);

        assertThatThrownBy(() -> postService.commentPost(postId, "Great post!", ownerId))
                .isInstanceOf(ApiException.class)
                .hasMessage("Post not found");
    }

    // --- bookmarkPost ---

    @Test
    void bookmarkPost_addsWhenNotBookmarked() {
        when(mongoTemplate.exists(any(Query.class), eq(User.class))).thenReturn(true);

        User afterAdd = new User();
        afterAdd.setId("user-1");
        afterAdd.getBookmarks().add(postId);

        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(User.class)))
                .thenReturn(null)      // pull attempt: not bookmarked yet
                .thenReturn(afterAdd); // addToSet attempt: succeeds

        List<String> result = postService.bookmarkPost(postId, "user-1");

        assertThat(result).containsExactly(postId);
    }

    @Test
    void bookmarkPost_removesWhenAlreadyBookmarked() {
        when(mongoTemplate.exists(any(Query.class), eq(User.class))).thenReturn(true);

        User afterRemoval = new User();
        afterRemoval.setId("user-1"); // bookmarks left empty

        when(mongoTemplate.findAndModify(any(Query.class), any(Update.class),
                any(FindAndModifyOptions.class), eq(User.class)))
                .thenReturn(afterRemoval); // pull attempt succeeds immediately

        List<String> result = postService.bookmarkPost(postId, "user-1");

        assertThat(result).isEmpty();
    }

    @Test
    void bookmarkPost_unknownUser_throws404() {
        when(mongoTemplate.exists(any(Query.class), eq(User.class))).thenReturn(false);

        assertThatThrownBy(() -> postService.bookmarkPost(postId, "ghost"))
                .isInstanceOf(ApiException.class)
                .hasMessage("User not found");

        verify(mongoTemplate, never()).findAndModify(any(), any(), any(), eq(User.class));
    }

    @Test
    void updatePost_creatorIsNull_throws403() {
        existingPost.setCreator(null); // simulates a legacy/malformed document
        when(postRepository.findById(postId)).thenReturn(Optional.of(existingPost));

        PostRequest request = new PostRequest();
        request.setTitle("Attempted title");

        assertThatThrownBy(() -> postService.updatePost(postId, request, null, ownerId))
                .isInstanceOf(ApiException.class)
                .hasMessage("You can only update your own posts");

        verify(postRepository, never()).save(any());
    }

    @Test
    void deletePost_creatorIsNull_throws403() {
        existingPost.setCreator(null);
        when(postRepository.findById(postId)).thenReturn(Optional.of(existingPost));

        assertThatThrownBy(() -> postService.deletePost(postId, ownerId))
                .isInstanceOf(ApiException.class)
                .hasMessage("You can only delete your own posts");

        verify(postRepository, never()).deleteById(any());
    }
}