package com.flicklog.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Mirrors models/post.model.js.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "posts")
public class Post {

    @Id
    @JsonProperty("_id")
    private String id;

    private String title;
    private String message;
    private String name;
    private String creator;
    private List<String> tags = new ArrayList<>();

    private String slug;

    private String selectedfile;

    private ImageData image;

    private List<String> likes = new ArrayList<>();

    private List<String> comments = new ArrayList<>();

    private Instant createdAt = Instant.now();
}
