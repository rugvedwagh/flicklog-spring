// src/test/java/com/flicklog/service/PostServiceTest.java
package com.flicklog.post.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flicklog.common.cache.RedisCacheService;
import com.flicklog.post.dto.request.PostRequest;
import com.flicklog.common.exception.ApiException;
import com.flicklog.post.model.Post;
import com.flicklog.post.repository.PostRepository;
import com.flicklog.user.repository.UserRepository;
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
    @Mock private UserRepository userRepository;
    @Mock private RedisCacheService redisCacheService;
    @Mock private CloudinaryService cloudinaryService;
    @Mock private ObjectMapper objectMapper;

    @InjectMocks private PostService postService;

    private Post existingPost;
    private String postId;

    @BeforeEach
    void setUp() {
        postId = new ObjectId().toHexString();
        existingPost = new Post();
        existingPost.setId(postId);
        existingPost.setTitle("Original title");
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
        when(postRepository.findById(postId)).thenReturn(Optional.of(existingPost));
        when(postRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Post result = postService.likePost(postId, "user-123");

        assertThat(result.getLikes()).containsExactly("user-123");
    }

    @Test
    void likePost_removesUserIdWhenAlreadyLiked() {
        existingPost.getLikes().add("user-123");
        when(postRepository.findById(postId)).thenReturn(Optional.of(existingPost));
        when(postRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Post result = postService.likePost(postId, "user-123");

        assertThat(result.getLikes()).isEmpty();
    }

    @Test
    void likePost_withoutUserId_throws401() {
        assertThatThrownBy(() -> postService.likePost(postId, null))
                .isInstanceOf(ApiException.class)
                .hasMessage("Unauthenticated");
    }

    @Test
    void commentPost_withBlankValue_throws400() {
        assertThatThrownBy(() -> postService.commentPost(postId, "  "))
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
    void fetchPosts_emptyResults_throws404() {
        when(redisCacheService.get(any())).thenReturn(null);
        when(postRepository.count()).thenReturn(0L);
        when(postRepository.findAllByOrderByIdDesc(any(Pageable.class))).thenReturn(List.of());

        assertThatThrownBy(() -> postService.fetchPosts(1))
                .isInstanceOf(ApiException.class)
                .hasMessage("No posts found");
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
    void fetchPostsBySearch_noResults_throws404() {
        when(redisCacheService.get(any())).thenReturn(null);
        when(postRepository.searchByTitleOrTags(any(), any())).thenReturn(List.of());

        assertThatThrownBy(() -> postService.fetchPostsBySearch("nothing", null))
                .isInstanceOf(ApiException.class);
    }

    // --- updatePost ---

    @Test
    void updatePost_withInvalidId_throws400() {
        PostRequest request = new PostRequest();

        assertThatThrownBy(() -> postService.updatePost("not-an-id", request, null))
                .isInstanceOf(ApiException.class)
                .hasMessage("Invalid post ID");
    }

    @Test
    void updatePost_notFound_throws404() {
        when(postRepository.findById(postId)).thenReturn(Optional.empty());
        PostRequest request = new PostRequest();

        assertThatThrownBy(() -> postService.updatePost(postId, request, null))
                .isInstanceOf(ApiException.class)
                .hasMessage("Post not found");
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

        Post result = postService.updatePost(postId, request, null);

        assertThat(result.getTitle()).isEqualTo("Updated title");
        assertThat(result.getTags()).containsExactly("newtag");
        verify(cloudinaryService, never()).destroy(any());
        verify(redisCacheService).delete("post:" + postId);
        verify(redisCacheService).deleteByPattern("posts:*");
    }

    // --- deletePost ---

    @Test
    void deletePost_withInvalidId_throws400() {
        assertThatThrownBy(() -> postService.deletePost("not-an-id"))
                .isInstanceOf(ApiException.class)
                .hasMessage("Invalid post ID");
    }

    @Test
    void deletePost_notFound_throws404() {
        when(postRepository.findById(postId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> postService.deletePost(postId))
                .isInstanceOf(ApiException.class)
                .hasMessage("Post not found");
    }

    @Test
    void deletePost_withImage_destroysCloudinaryImageAndDeletesFromRepo() {
        existingPost.setImage(new ImageData("http://cloud/img.png", "public-id-123", "img.png"));
        when(postRepository.findById(postId)).thenReturn(Optional.of(existingPost));

        postService.deletePost(postId);

        verify(cloudinaryService).destroy("public-id-123");
        verify(postRepository).deleteById(postId);
        verify(redisCacheService).delete("post:" + postId);
        verify(redisCacheService).deleteByPattern("posts:*");
    }

    // --- commentPost (happy path) ---

    @Test
    void commentPost_withValidValue_addsCommentAndClearsCache() {
        when(postRepository.findById(postId)).thenReturn(Optional.of(existingPost));
        when(postRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        Post result = postService.commentPost(postId, "Great post!");

        assertThat(result.getComments()).containsExactly("Great post!");
        verify(redisCacheService).delete("post:" + postId);
    }

    // --- bookmarkPost ---

    @Test
    void bookmarkPost_addsWhenNotBookmarked() {
        User user = new User();
        user.setId("user-1");
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));

        List<String> result = postService.bookmarkPost(postId, "user-1");

        assertThat(result).containsExactly(postId);
        verify(userRepository).save(user);
    }

    @Test
    void bookmarkPost_removesWhenAlreadyBookmarked() {
        User user = new User();
        user.setId("user-1");
        user.getBookmarks().add(postId);
        when(userRepository.findById("user-1")).thenReturn(Optional.of(user));

        List<String> result = postService.bookmarkPost(postId, "user-1");

        assertThat(result).isEmpty();
    }

    @Test
    void bookmarkPost_unknownUser_throws404() {
        when(userRepository.findById("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> postService.bookmarkPost(postId, "ghost"))
                .isInstanceOf(ApiException.class)
                .hasMessage("User not found");
    }
}