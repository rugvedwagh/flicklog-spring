package com.flicklog.dto.response;

import com.flicklog.model.Post;
import lombok.AllArgsConstructor;
import lombok.Data;

import java.util.List;

/**
 * Mirrors { data, currentPage, numberOfPages } from fetchPosts in post.controllers.js.
 */
@Data
@AllArgsConstructor
public class PostsPageResponse {
    private List<Post> data;
    private int currentPage;
    private int numberOfPages;
}
