package com.flicklog.dto.request;

import lombok.Data;

/**
 * Bound from multipart form fields (alongside an optional "selectedfile" image part),
 * matching createPost/updatePost in post.controllers.js.
 */
@Data
public class PostRequest {
    private String title;
    private String message;
    private String name;
    private String tags;  // comma-separated, split server-side same as the original
    private String slug;
}
