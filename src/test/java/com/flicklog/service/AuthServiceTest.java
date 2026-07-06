// src/test/java/com/flicklog/service/AuthServiceTest.java
package com.flicklog.service;

import com.flicklog.dto.request.LoginRequest;
import com.flicklog.dto.request.RegisterRequest;
import com.flicklog.exception.ApiException;
import com.flicklog.model.User;
import com.flicklog.repository.UserRepository;
import com.flicklog.security.JwtTokenService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuthServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private PasswordEncoder passwordEncoder;
    @Mock private JwtTokenService jwtTokenService;

    @InjectMocks private AuthService authService;

    private User existingUser;

    @BeforeEach
    void setUp() {
        existingUser = new User();
        existingUser.setId("507f1f77bcf86cd799439011");
        existingUser.setEmail("test@example.com");
        existingUser.setPassword("hashed-password");
    }

    @Test
    void login_withCorrectCredentials_returnsAuthResult() {
        LoginRequest request = new LoginRequest();
        request.setEmail("test@example.com");
        request.setPassword("password123");

        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.matches("password123", "hashed-password")).thenReturn(true);
        when(jwtTokenService.generateCsrfToken()).thenReturn("csrf-token");
        when(jwtTokenService.generateRefreshToken(any())).thenReturn("refresh-token");
        when(jwtTokenService.generateAccessToken(any())).thenReturn("access-token");
        when(userRepository.save(any())).thenReturn(existingUser);

        AuthResult result = authService.login(request, "test-agent");

        assertThat(result.getAccessToken()).isEqualTo("access-token");
        assertThat(result.getCsrfToken()).isEqualTo("csrf-token");
        verify(userRepository).save(existingUser);
    }

    @Test
    void login_withWrongPassword_throws400() {
        LoginRequest request = new LoginRequest();
        request.setEmail("test@example.com");
        request.setPassword("wrongpass");

        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(existingUser));
        when(passwordEncoder.matches("wrongpass", "hashed-password")).thenReturn(false);

        assertThatThrownBy(() -> authService.login(request, "test-agent"))
                .isInstanceOf(ApiException.class)
                .hasMessage("Incorrect password");
    }

    @Test
    void login_withUnknownEmail_throws404() {
        LoginRequest request = new LoginRequest();
        request.setEmail("ghost@example.com");
        request.setPassword("password123");

        when(userRepository.findByEmail("ghost@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.login(request, "test-agent"))
                .isInstanceOf(ApiException.class)
                .hasMessage("User not found");
    }

    @Test
    void register_withMismatchedPasswords_throws400() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("new@example.com");
        request.setPassword("password123");
        request.setConfirmPassword("different");
        request.setFirstName("Jane");
        request.setLastName("Doe");

        when(userRepository.findByEmail("new@example.com")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> authService.register(request, "test-agent"))
                .isInstanceOf(ApiException.class)
                .hasMessage("Passwords don't match");
    }

    @Test
    void register_withExistingEmail_throws400() {
        RegisterRequest request = new RegisterRequest();
        request.setEmail("test@example.com");
        request.setPassword("password123");
        request.setConfirmPassword("password123");
        request.setFirstName("Jane");
        request.setLastName("Doe");

        when(userRepository.findByEmail("test@example.com")).thenReturn(Optional.of(existingUser));

        assertThatThrownBy(() -> authService.register(request, "test-agent"))
                .isInstanceOf(ApiException.class)
                .hasMessage("Email is already in use");
    }
}