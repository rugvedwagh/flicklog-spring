package com.flicklog.dto.request;

import lombok.Data;

@Data
public class BookmarkRequest {
    private String postId;
    private String userId;
}
