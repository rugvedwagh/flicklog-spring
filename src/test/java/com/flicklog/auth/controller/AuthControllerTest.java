package com.flicklog.auth.controller;

import com.flicklog.common.config.AppProperties;
import com.flicklog.common.config.SecurityConfig;
import com.flicklog.common.exception.ApiException;
import com.flicklog.common.exception.GlobalExceptionHandler;
import com.flicklog.user.model.User;
import com.flicklog.auth.security.JwtAuthFilter;
import com.flicklog.auth.security.JwtTokenService;
import com.flicklog.auth.service.AuthResult;
import com.flicklog.auth.service.AuthService;
import com.flicklog.auth.service.RefreshResult;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AuthController.class)
@Import({SecurityConfig.class, JwtAuthFilter.class, GlobalExceptionHandler.class, AppProperties.class})
class AuthControllerTest {

    @Autowired private MockMvc mockMvc;

    @MockBean private AuthService authService;
    @MockBean private JwtTokenService jwtTokenService; // needed by JwtAuthFilter/SecurityConfig

    // --- register ---

    @Test
    void register_withValidRequest_returns201WithCookieAndBody() throws Exception {
        User user = new User();
        user.setId("507f1f77bcf86cd799439011");
        user.setEmail("new@example.com");

        AuthResult result = new AuthResult(user, "access-token", "refresh-token-value", "csrf-token", "session-id");
        when(authService.register(any(), any())).thenReturn(result);

        mockMvc.perform(post("/auth/register")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"new@example.com\",\"password\":\"pass123\",\"confirmPassword\":\"pass123\",\"firstName\":\"A\",\"lastName\":\"B\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accessToken").value("access-token"))
                .andExpect(jsonPath("$.csrfToken").value("csrf-token"))
                .andExpect(jsonPath("$.sessionId").value("session-id"))
                .andExpect(header().string("Set-Cookie", containsString("refreshToken=refresh-token-value")))
                .andExpect(header().string("Set-Cookie", containsString("HttpOnly")))
                .andExpect(header().string("Set-Cookie", containsString("Secure")))
                .andExpect(header().string("Set-Cookie", containsString("SameSite=None")));
    }

    // --- login ---

    @Test
    void login_withValidRequest_returns200WithCookie() throws Exception {
        User user = new User();
        user.setId("507f1f77bcf86cd799439011");

        AuthResult result = new AuthResult(user, "access-token", "refresh-token-value", "csrf-token", "session-id");
        when(authService.login(any(), any(), any())).thenReturn(result);

        mockMvc.perform(post("/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"test@example.com\",\"password\":\"pass123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("access-token"))
                .andExpect(header().string("Set-Cookie", containsString("refreshToken=refresh-token-value")));
    }
    // --- POST /auth/refresh-token/secure ---

    @Test
    void refreshTokenSecure_withValidCsrfAndToken_returnsNewAccessToken() throws Exception {
        doNothing().when(authService).verifyCsrf("refresh-tok", "session-1", "csrf-tok");
        when(authService.refreshAccessToken("refresh-tok", "session-1"))
                .thenReturn(new RefreshResult("new-access-token", "new-refresh-token"));

        mockMvc.perform(post("/auth/refresh-token/secure")
                        .cookie(new Cookie("refreshToken", "refresh-tok"))
                        .header("x-session-id", "session-1")
                        .header("x-xsrf-token", "csrf-tok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.accessToken").value("new-access-token"))
                .andExpect(header().string("Set-Cookie", containsString("refreshToken=new-refresh-token")));
    }

    @Test
    void refreshTokenSecure_withInvalidCsrf_returns403() throws Exception {
        doThrow(new ApiException("Invalid session or CSRF token", 403))
                .when(authService).verifyCsrf(any(), any(), any());

        mockMvc.perform(post("/auth/refresh-token/secure")
                        .cookie(new Cookie("refreshToken", "refresh-tok"))
                        .header("x-session-id", "session-1")
                        .header("x-xsrf-token", "wrong-csrf"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.message").value("Invalid session or CSRF token"));
    }

    // --- logout ---

    @Test
    void logout_withValidCsrfAndSession_returns200AndClearsCookie() throws Exception {
        doNothing().when(authService).verifyCsrf("refresh-tok", "session-1", "csrf-tok");
        doNothing().when(authService).logout("session-1");

        mockMvc.perform(post("/auth/logout")
                        .cookie(new Cookie("refreshToken", "refresh-tok"))
                        .header("x-session-id", "session-1")
                        .header("x-xsrf-token", "csrf-tok"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Logged out successfully"))
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));

        verify(authService).logout("session-1");
    }

    @Test
    void logout_withInvalidCsrf_returns403AndDoesNotLogOut() throws Exception {
        doThrow(new ApiException("Invalid session or CSRF token", 403))
                .when(authService).verifyCsrf(any(), any(), any());

        mockMvc.perform(post("/auth/logout")
                        .cookie(new Cookie("refreshToken", "refresh-tok"))
                        .header("x-session-id", "session-1")
                        .header("x-xsrf-token", "wrong-csrf"))
                .andExpect(status().isForbidden());

        verify(authService, never()).logout(any());
    }
}