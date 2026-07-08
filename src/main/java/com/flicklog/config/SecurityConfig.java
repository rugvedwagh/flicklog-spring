package com.flicklog.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flicklog.security.JwtAuthFilter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;
import java.util.Map;

/**
 * Mirrors the security-relevant parts of server.js + auth.middleware.js:
 *  - helmet()/compression() -> Spring Boot defaults + servlet compression (see application.yml)
 *  - cors({ origin: REACT_APP_DOMAIN, credentials: true })
 *  - stateless JWT auth instead of express-session
 *  - per-route public/protected split matching the original routers exactly
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;
    private final AppProperties appProperties;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter, AppProperties appProperties) {
        this.jwtAuthFilter = jwtAuthFilter;
        this.appProperties = appProperties;
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        // bcrypt with a cost factor of 12, matching bcrypt.hash(password, 12) in the original
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        ObjectMapper objectMapper = new ObjectMapper();

        http
                .csrf(csrf -> csrf.disable()) // handled manually via the x-xsrf-token header, like csrf.middleware.js
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // auth routes
                        .requestMatchers("/auth/register", "/auth/login", "/auth/logout").permitAll()
                        .requestMatchers("/auth/refresh-token").permitAll()
                        .requestMatchers("/auth/refresh-token/secure").permitAll() // csrf checked manually, not via JWT filter
                        // post routes - public reads, protected writes (matches post.routes.js exactly)
                        .requestMatchers(HttpMethod.GET, "/posts/search", "/posts", "/posts/*").permitAll()
                        .requestMatchers(HttpMethod.POST, "/posts").authenticated()
                        .requestMatchers(HttpMethod.PATCH, "/posts/*").authenticated()
                        .requestMatchers(HttpMethod.DELETE, "/posts/*").authenticated()
                        .requestMatchers(HttpMethod.PATCH, "/posts/*/likePost").authenticated()
                        .requestMatchers(HttpMethod.POST, "/posts/*/commentPost").authenticated()
                        .requestMatchers(HttpMethod.POST, "/posts/bookmarks/add").authenticated()
                        // user routes - both require a valid access token
                        .requestMatchers("/user/**").authenticated()
                        .requestMatchers("/").permitAll()
                        .anyRequest().permitAll()
                )
                .exceptionHandling(ex -> ex.authenticationEntryPoint((request, response, authException) -> {
                    response.setStatus(401);
                    response.setContentType("application/json");
                    response.getWriter().write(objectMapper.writeValueAsString(
                            Map.of("message", "Authorization header is missing")));
                }))
                .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        configuration.setAllowedOrigins(List.of(appProperties.getCors().getAllowedOrigin()));
        configuration.setAllowedMethods(List.of("GET", "POST", "PATCH", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(true); // matches cors({ credentials: true }) - needed for the refreshToken cookie

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/**", configuration);
        return source;
    }
}
