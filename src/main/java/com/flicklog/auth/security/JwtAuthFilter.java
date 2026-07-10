package com.flicklog.auth.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.Collections;
import java.util.Map;

/**
 * Mirrors middleware/auth.middleware.js's verifyAccessToken.
 *
 * On a valid Bearer token, populates the SecurityContext (so Spring's
 * authorizeHttpRequests().authenticated() rules work) AND sets a request
 * attribute "userId" so controllers can read it exactly like req.userId
 * in the original Express code.
 *
 * Unlike the original, this filter runs on every request but only *enforces*
 * a valid token on routes marked .authenticated() in SecurityConfig - if no
 * Authorization header is present on a public route, it just passes through,
 * same as those routes never having the middleware attached in Express.
 */

@Slf4j
@Component
public class JwtAuthFilter extends OncePerRequestFilter {

    private final JwtTokenService jwtTokenService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    public JwtAuthFilter(JwtTokenService jwtTokenService) {
        this.jwtTokenService = jwtTokenService;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {

        String authHeader = request.getHeader("Authorization");

        if (authHeader != null) {
            String[] parts = authHeader.split(" ", 2);
            if (parts.length != 2 || !"bearer".equalsIgnoreCase(parts[0])) {
                log.warn("Rejected request: malformed Authorization header on {}", request.getRequestURI());
                writeError(response, 401, "Invalid Authorization header format. Use: Bearer <token>");
                return;
            }

            String token = parts[1];
            try {
                Claims claims = jwtTokenService.verifyAccessToken(token);
                String userId = claims.get("id", String.class);

                request.setAttribute("userId", userId);

                var authentication = new UsernamePasswordAuthenticationToken(
                        userId, null, Collections.emptyList());
                SecurityContextHolder.getContext().setAuthentication(authentication);
            } catch (JwtException e) {
                log.debug("Rejected request: invalid/expired access token on {} ({})", request.getRequestURI(), e.getMessage());
                writeError(response, 401, "Invalid or expired access token");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    // Filters run before DispatcherServlet, so @RestControllerAdvice can't catch
    // exceptions thrown here - write the same {message} JSON shape directly instead.
    private void writeError(HttpServletResponse response, int status, String message) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        response.getWriter().write(objectMapper.writeValueAsString(Map.of("message", message)));
    }
}
