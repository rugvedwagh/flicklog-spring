package com.flicklog.controller;

import com.flicklog.config.AppProperties;
import com.flicklog.config.SecurityConfig;
import com.flicklog.exception.ApiException;
import com.flicklog.exception.GlobalExceptionHandler;
import com.flicklog.model.User;
import com.flicklog.security.JwtAuthFilter;
import com.flicklog.security.JwtTokenService;
import com.flicklog.service.AuthResult;
import com.flicklog.service.AuthService;
import com.flicklog.service.RefreshResult;
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
        when(authService.login(any(), any())).thenReturn(result);

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
    void logout_withSessionIdInBody_returns200AndClearsCookie() throws Exception {
        doNothing().when(authService).logout("session-from-body");

        mockMvc.perform(post("/auth/logout")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sessionId\":\"session-from-body\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Logged out successfully"))
                .andExpect(header().string("Set-Cookie", containsString("Max-Age=0")));

        verify(authService).logout("session-from-body");
    }

    @Test
    void logout_withSessionIdHeaderFallback_usesHeaderWhenBodyMissing() throws Exception {
        doNothing().when(authService).logout("session-from-header");

        mockMvc.perform(post("/auth/logout")
                        .header("x-session-id", "session-from-header"))
                .andExpect(status().isOk());

        verify(authService).logout("session-from-header");
    }
}