// src/test/java/com/flicklog/controller/PostControllerTest.java
package com.flicklog.controller;

import com.flicklog.config.SecurityConfig;
import com.flicklog.exception.GlobalExceptionHandler;
import com.flicklog.model.Post;
import com.flicklog.security.JwtAuthFilter;
import com.flicklog.security.JwtTokenService;
import com.flicklog.service.PostService;
import io.jsonwebtoken.Claims;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;

import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(PostController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, GlobalExceptionHandler.class})
class PostControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private PostService postService;
    @MockBean private JwtTokenService jwtTokenService; // needed by JwtAuthFilter/SecurityConfig

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
}