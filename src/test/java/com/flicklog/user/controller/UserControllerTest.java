package com.flicklog.user.controller;

import com.flicklog.common.config.AppProperties;
import com.flicklog.common.config.SecurityConfig;
import com.flicklog.common.exception.GlobalExceptionHandler;
import com.flicklog.user.model.User;
import com.flicklog.auth.security.JwtAuthFilter;
import com.flicklog.auth.security.JwtTokenService;
import com.flicklog.user.service.UserService;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(UserController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, GlobalExceptionHandler.class, AppProperties.class})
class UserControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private UserService userService;
    @MockBean private JwtTokenService jwtTokenService; // needed by JwtAuthFilter/SecurityConfig

    private static final String USER_ID = "507f1f77bcf86cd799439011";

    // --- PATCH /user/{id}/update ---

    @Test
    void updateUser_withoutAuthHeader_returns401() throws Exception {
        mockMvc.perform(patch("/user/" + USER_ID + "/update")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"New Name\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authorization header is missing"));
    }

    @Test
    void updateUser_withGarbledAuthHeader_returns401() throws Exception {
        mockMvc.perform(patch("/user/" + USER_ID + "/update")
                        .header("Authorization", "NotBearer sometoken")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"New Name\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Invalid Authorization header format. Use: Bearer <token>"));
    }

    @Test
    void updateUser_withValidToken_returns200() throws Exception {
        Claims claims = Jwts.claims().add("id", USER_ID).build();
        when(jwtTokenService.verifyAccessToken("valid-token")).thenReturn(claims);

        User updated = new User();
        updated.setId(USER_ID);
        updated.setName("Updated Name");
        when(userService.updateUser(eq(USER_ID), eq(USER_ID), any())).thenReturn(updated);

        mockMvc.perform(patch("/user/" + USER_ID + "/update")
                        .header("Authorization", "Bearer valid-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Updated Name\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Updated Name"));
    }

    // --- GET /user/account/{id} ---

    @Test
    void fetchUserData_withoutAuthHeader_returns401() throws Exception {
        mockMvc.perform(get("/user/account/" + USER_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.message").value("Authorization header is missing"));
    }

    @Test
    void fetchUserData_withValidToken_returns200() throws Exception {
        Claims claims = Jwts.claims().add("id", USER_ID).build();
        when(jwtTokenService.verifyAccessToken("valid-token")).thenReturn(claims);

        User user = new User();
        user.setId(USER_ID);
        user.setName("Jane Doe");
        when(userService.fetchUserData(USER_ID)).thenReturn(user);

        mockMvc.perform(get("/user/account/" + USER_ID)
                        .header("Authorization", "Bearer valid-token"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name").value("Jane Doe"));
    }
}