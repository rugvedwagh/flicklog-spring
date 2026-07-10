package com.flicklog.auth.service;

import com.flicklog.user.model.User;
import lombok.AllArgsConstructor;
import lombok.Data;

/**
 * Internal carrier for login/register output. refreshToken is only used by the
 * controller to set the httpOnly cookie - it's never put in the JSON body,
 * matching the original where the cookie and JSON response are set separately.
 */
@Data
@AllArgsConstructor
public class AuthResult {
    private User user;
    private String accessToken;
    private String refreshToken;
    private String csrfToken;
    private String sessionId;
}
