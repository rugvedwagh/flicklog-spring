package com.flicklog.post.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;

/**
 * A single comment on a post. Previously stored as a bare String with no
 * author, id, or timestamp - this makes moderation (delete/edit a single
 * comment) and attribution (who said this) possible.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class Comment {

    @JsonProperty("_id")
    private String id;

    private String authorId;
    private String value;
    private Instant createdAt;
}