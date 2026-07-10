package com.flicklog.auth.dto.response;

import com.flicklog.user.model.User;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Mirrors the { result, accessToken, csrfToken, sessionId } shape
 * returned by logIn/register in auth.controllers.js.
 */
@Data
@AllArgsConstructor
public class AuthResponse {
    private User result;
    private String accessToken;
    private String csrfToken;
    private String sessionId;
}
