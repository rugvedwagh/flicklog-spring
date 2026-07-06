// src/test/java/com/flicklog/service/PostServiceTest.java
package com.flicklog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flicklog.dto.request.PostRequest;
import com.flicklog.exception.ApiException;
import com.flicklog.model.Post;
import com.flicklog.repository.PostRepository;
import com.flicklog.repository.UserRepository;
import org.bson.types.ObjectId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

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
}