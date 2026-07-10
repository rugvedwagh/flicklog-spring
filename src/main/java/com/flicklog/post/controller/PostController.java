package com.flicklog.post.controller;

import com.flicklog.post.dto.request.BookmarkRequest;
import com.flicklog.post.dto.request.CommentRequest;
import com.flicklog.post.dto.request.PostRequest;
import com.flicklog.post.dto.response.PostsPageResponse;
import com.flicklog.post.model.Post;
import com.flicklog.post.service.PostService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;

/**
 * Mirrors routes/post.routes.js + controllers/post.controllers.js.
 */
@RestController
@RequestMapping("/posts")
public class PostController {

    private final PostService postService;

    public PostController(PostService postService) {
        this.postService = postService;
    }

    @GetMapping("/search")
    public Map<String, List<Post>> fetchPostsBySearch(@RequestParam(defaultValue = "") String searchQuery,
                                                      @RequestParam(defaultValue = "") String tags) {
        return Map.of("data", postService.fetchPostsBySearch(searchQuery, tags));
    }

    @GetMapping("/{slugId}")
    public Post fetchPost(@PathVariable String slugId) {
        return postService.fetchPost(slugId);
    }

    @GetMapping
    public PostsPageResponse fetchPosts(@RequestParam(defaultValue = "1") int page) {
        return postService.fetchPosts(page);
    }

    @PostMapping(consumes = "multipart/form-data")
    public Post createPost(@Valid @ModelAttribute PostRequest request,
                           @RequestParam(value = "selectedfile", required = false) MultipartFile file,
                           HttpServletRequest httpRequest) {
        String creatorId = (String) httpRequest.getAttribute("userId");
        return postService.createPost(request, file, creatorId);
    }

    @PatchMapping(value = "/{id}", consumes = "multipart/form-data")
    public Post updatePost(@PathVariable String id, @Valid @ModelAttribute PostRequest request,
                           @RequestParam(value = "selectedfile", required = false) MultipartFile file) {
        return postService.updatePost(id, request, file);
    }

    @DeleteMapping("/{id}")
    public Map<String, String> deletePost(@PathVariable String id) {
        postService.deletePost(id);
        return Map.of("message", "Post deleted successfully!");
    }

    @PatchMapping("/{id}/likePost")
    public Post likePost(@PathVariable String id, HttpServletRequest httpRequest) {
        String userId = (String) httpRequest.getAttribute("userId");
        return postService.likePost(id, userId);
    }

    @PostMapping("/{id}/commentPost")
    public Post commentPost(@PathVariable String id, @Valid @RequestBody CommentRequest request) {
        return postService.commentPost(id, request.getValue());
    }

    @PostMapping("/bookmarks/add")
    public Map<String, List<String>> bookmarkPost(@Valid @RequestBody BookmarkRequest request) {
        return Map.of("updatedBookmarks", postService.bookmarkPost(request.getPostId(), request.getUserId()));
    }
}
