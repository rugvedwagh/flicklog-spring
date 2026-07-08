package com.flicklog.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class BookmarkRequest {
    @NotBlank(message = "Post ID is required")
    private String postId;

    @NotBlank(message = "User ID is required")
    private String userId;
}