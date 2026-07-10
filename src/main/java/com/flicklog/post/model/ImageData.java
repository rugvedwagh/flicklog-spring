package com.flicklog.post.model;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Mirrors the `image` sub-object on post.model.js (url / publicId / originalName).
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ImageData {
    private String url;
    private String publicId;
    private String originalName;
}
