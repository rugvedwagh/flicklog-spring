package com.flicklog;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.flicklog.dto.request.RegisterRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import org.testcontainers.containers.MongoDBContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@Testcontainers
@SpringBootTest
class PostIntegrationTest {

    @Container
    static MongoDBContainer mongoDBContainer = new MongoDBContainer("mongo:7");

    @DynamicPropertySource
    static void mongoProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.mongodb.uri", mongoDBContainer::getReplicaSetUrl);
    }

    @Autowired private WebApplicationContext context;
    private MockMvc mockMvc;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void registerThenCreatePostThenFetchIt_fullFlowWorks() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        // --- 1. register a user and grab the access token ---
        RegisterRequest register = new RegisterRequest();
        register.setEmail("post-flow@test.com");
        register.setPassword("password123");
        register.setConfirmPassword("password123");
        register.setFirstName("Post");
        register.setLastName("Flow");

        String registerResponse = mockMvc.perform(post("/auth/register")
                        .contentType("application/json")
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();

        String accessToken = objectMapper.readTree(registerResponse).get("accessToken").asText();

        // --- 2. create a post using that token ---
        String createResponse = mockMvc.perform(multipart("/posts")
                        .param("title", "Integration Test Post")
                        .param("message", "Hello from the integration test")
                        .param("name", "Post Flow")
                        .param("tags", "java,spring")
                        .param("slug", "integration-test-post")
                        .header("Authorization", "Bearer " + accessToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Integration Test Post"))
                .andReturn().getResponse().getContentAsString();

        JsonNode createdPost = objectMapper.readTree(createResponse);
        String postId = createdPost.get("_id").asText();
        String slug = createdPost.get("slug").asText();

        // --- 3. fetch it back through the real cache-or-DB path ---
        mockMvc.perform(get("/posts/" + slug + "-" + postId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.title").value("Integration Test Post"))
                .andExpect(jsonPath("$.tags[0]").value("java"))
                .andExpect(jsonPath("$.tags[1]").value("spring"));
    }

    @Test
    void createPost_withoutAuthToken_returns401() throws Exception {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();

        mockMvc.perform(multipart("/posts")
                        .param("title", "Should not be created")
                        .param("message", "Blocked")
                        .param("name", "Nobody")
                        .param("slug", "blocked-post"))
                .andExpect(status().isUnauthorized());
    }
}