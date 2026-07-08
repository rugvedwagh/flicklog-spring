package com.flicklog.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class JwtAuthFilterTest {

    @Mock private JwtTokenService jwtTokenService;
    @Mock private FilterChain filterChain;

    private JwtAuthFilter filter;

    @BeforeEach
    void setUp() {
        filter = new JwtAuthFilter(jwtTokenService);
    }

    @AfterEach
    void clearSecurityContext() {
        // JwtAuthFilter writes into a ThreadLocal-backed SecurityContext -
        // clear it between tests so one test's authentication can't leak into the next.
        SecurityContextHolder.clearContext();
    }

    @Test
    void noAuthorizationHeader_passesThroughWithoutSettingAuthentication() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        verify(filterChain).doFilter(request, response);
        assertThat(SecurityContextHolder.getContext().getAuthentication()).isNull();
        verifyNoInteractions(jwtTokenService);
    }

    @Test
    void validBearerToken_setsAuthenticationAndUserIdAttribute() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer valid-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        Claims claims = Jwts.claims().add("id", "user-123").build();
        when(jwtTokenService.verifyAccessToken("valid-token")).thenReturn(claims);

        filter.doFilterInternal(request, response, filterChain);

        assertThat(request.getAttribute("userId")).isEqualTo("user-123");
        assertThat(SecurityContextHolder.getContext().getAuthentication().getName()).isEqualTo("user-123");
        verify(filterChain).doFilter(request, response);
    }

    @Test
    void malformedHeader_missingBearerPrefix_returns401AndStopsChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "NotBearer sometoken");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString())
                .contains("Invalid Authorization header format. Use: Bearer <token>");
        verify(filterChain, never()).doFilter(any(), any());
    }

    @Test
    void invalidOrExpiredToken_returns401AndStopsChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer expired-token");
        MockHttpServletResponse response = new MockHttpServletResponse();

        when(jwtTokenService.verifyAccessToken("expired-token")).thenThrow(new JwtException("expired"));

        filter.doFilterInternal(request, response, filterChain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).contains("Invalid or expired access token");
        verify(filterChain, never()).doFilter(any(), any());
    }
}