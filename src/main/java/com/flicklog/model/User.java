package com.flicklog.model;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;

import java.util.ArrayList;
import java.util.List;

/**
 * Mirrors models/user.model.js.
 *
 * password/sessions are @JsonIgnore'd by default since every original controller
 * manually stripped them before sending a response (user.toObject() + delete ...).
 * Doing it declaratively here means new endpoints can't accidentally leak them.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Document(collection = "users")
public class User {

    @Id
    @JsonProperty("_id")
    private String id;

    private String name;

    private String email;

    @JsonIgnore
    private String password;

    @Field("bookmarks")
    private List<String> bookmarks = new ArrayList<>();

    @JsonIgnore
    private List<Session> sessions = new ArrayList<>();
}
