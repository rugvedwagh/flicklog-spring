// src/test/java/com/flicklog/controller/PostControllerTest.java
package com.flicklog.post.controller;

import com.flicklog.common.config.AppProperties;
import com.flicklog.common.config.SecurityConfig;
import com.flicklog.common.exception.ApiException;
import org.springframework.http.HttpMethod;
import com.flicklog.common.exception.GlobalExceptionHandler;
import com.flicklog.post.model.Post;
import com.flicklog.auth.security.JwtAuthFilter;
import com.flicklog.auth.security.JwtTokenService;
import com.flicklog.post.service.PostService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PostController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, GlobalExceptionHandler.class, AppProperties.class})
class PostControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private PostService postService;
    @MockBean
    private JwtTokenService jwtTokenService; // needed by JwtAuthFilter/SecurityConfig

    @Test
    void getPost_publicRoute_returns200WithoutAuth() throws Exception {
        Post post = new Post();
        post.setId("507f1f77bcf86cd799439011");
        post.setTitle("Hello");

        when(postService.fetchPost("hello-507f1f77bcf86cd799439011")).thenReturn(post);

        mockMvc.perform(get("/posts/hello-507f1f77bcf86cd799439011"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Hello"));
    }

    @Test
    void likePost_withoutAuthHeader_returns401() throws Exception {
        mockMvc.perform(patch("/posts/507f1f77bcf86cd799439011/likePost"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authorization header is missing"));
    }

    @Test
    void likePost_withValidToken_returns200() throws Exception {
        Claims claims = io.jsonwebtoken.Jwts.claims().add("id", "user-123").build();
        when(jwtTokenService.verifyAccessToken("valid-token")).thenReturn(claims);

        Post liked = new Post();
        liked.setId("507f1f77bcf86cd799439011");
        when(postService.likePost("507f1f77bcf86cd799439011", "user-123")).thenReturn(liked);

        mockMvc.perform(patch("/posts/507f1f77bcf86cd799439011/likePost")
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk());
    }

    @Test
    void likePost_withGarbledAuthHeader_returns401() throws Exception {
        mockMvc.perform(patch("/posts/507f1f77bcf86cd799439011/likePost")
                        .header("Authorization", "NotBearer sometoken"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid Authorization header format. Use: Bearer <token>"));
    }

    @Test
    void updatePost_asOwner_returns200() throws Exception {
        Claims claims = Jwts.claims().add("id", "owner-1").build();
        when(jwtTokenService.verifyAccessToken("owner-token")).thenReturn(claims);

        Post updated = new Post();
        updated.setId("507f1f77bcf86cd799439011");
        updated.setTitle("Updated title");

        when(postService.updatePost(eq("507f1f77bcf86cd799439011"), any(), any(), eq("owner-1")))
                .thenReturn(updated);

        mockMvc.perform(multipart(HttpMethod.PATCH, "/posts/507f1f77bcf86cd799439011")
                        .param("title", "Updated title")
                        .param("message", "Updated message")
                        .param("name", "Author")
                        .param("slug", "updated-slug")
                        .header("Authorization", "Bearer owner-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Updated title"));
    }

    @Test
    void updatePost_notOwner_returns403() throws Exception {
        Claims claims = Jwts.claims().add("id", "not-the-owner").build();
        when(jwtTokenService.verifyAccessToken("intruder-token")).thenReturn(claims);

        when(postService.updatePost(eq("507f1f77bcf86cd799439011"), any(), any(), eq("not-the-owner")))
                .thenThrow(new ApiException("You can only update your own posts", 403));

        mockMvc.perform(multipart(HttpMethod.PATCH, "/posts/507f1f77bcf86cd799439011")
                        .param("title", "Hacked title")
                        .param("message", "Hacked message")
                        .param("name", "Author")
                        .param("slug", "hacked-slug")
                        .header("Authorization", "Bearer intruder-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("You can only update your own posts"));
    }

    @Test
    void deletePost_asOwner_returns200() throws Exception {
        Claims claims = Jwts.claims().add("id", "owner-1").build();
        when(jwtTokenService.verifyAccessToken("owner-token")).thenReturn(claims);

        mockMvc.perform(delete("/posts/507f1f77bcf86cd799439011")
                        .header("Authorization", "Bearer owner-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Post deleted successfully!"));

        verify(postService).deletePost("507f1f77bcf86cd799439011", "owner-1");
    }

    @Test
    void deletePost_notOwner_returns403() throws Exception {
        Claims claims = Jwts.claims().add("id", "not-the-owner").build();
        when(jwtTokenService.verifyAccessToken("intruder-token")).thenReturn(claims);

        doThrow(new ApiException("You can only delete your own posts", 403))
                .when(postService).deletePost("507f1f77bcf86cd799439011", "not-the-owner");

        mockMvc.perform(delete("/posts/507f1f77bcf86cd799439011")
                        .header("Authorization", "Bearer intruder-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("You can only delete your own posts"));
    }

    @Test
    void bookmarkPost_ignoresClientSuppliedUserId_usesAuthenticatedUser() throws Exception {
        Claims claims = Jwts.claims().add("id", "authenticated-user").build();
        when(jwtTokenService.verifyAccessToken("valid-token")).thenReturn(claims);

        when(postService.bookmarkPost("507f1f77bcf86cd799439011", "authenticated-user"))
                .thenReturn(List.of("507f1f77bcf86cd799439011"));

        // "userId" here is not a field on BookmarkRequest anymore - this proves that even
        // if a client sends it (spoofing another user), it's silently ignored by Jackson
        // and never reaches the service. Only the token-derived id is ever used.
        mockMvc.perform(post("/posts/bookmarks/add")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"postId\":\"507f1f77bcf86cd799439011\",\"userId\":\"victim-user\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.updatedBookmarks[0]").value("507f1f77bcf86cd799439011"));

        // the key assertion: the service was called with the authenticated user's id,
        // never with "victim-user" from the request body
        verify(postService).bookmarkPost("507f1f77bcf86cd799439011", "authenticated-user");
        verify(postService, never()).bookmarkPost(anyString(), eq("victim-user"));
    }
}