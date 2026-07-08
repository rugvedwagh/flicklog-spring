package com.flicklog.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Bound from multipart form fields (alongside an optional "selectedfile" image part),
 * matching createPost/updatePost in post.controllers.js.
 */
@Data
public class PostRequest {
    @NotBlank(message = "Title is required")
    private String title;

    @NotBlank(message = "Message is required")
    private String message;

    @NotBlank(message = "Name is required")
    private String name;

    private String tags;  // optional, comma-separated

    @NotBlank(message = "Slug is required")
    private String slug;
}