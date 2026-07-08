package com.flicklog.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flicklog.dto.request.UpdateUserRequest;
import com.flicklog.exception.ApiException;
import com.flicklog.model.User;
import com.flicklog.repository.UserRepository;
import lombok.extern.slf4j.Slf4j;
import org.bson.types.ObjectId;
import org.springframework.stereotype.Service;

/**
 * Mirrors controllers/user.controllers.js.
 */

@Slf4j
@Service
public class UserService {

    private final UserRepository userRepository;
    private final RedisCacheService redisCacheService;
    private final ObjectMapper objectMapper;

    public UserService(UserRepository userRepository, RedisCacheService redisCacheService, ObjectMapper objectMapper) {
        this.userRepository = userRepository;
        this.redisCacheService = redisCacheService;
        this.objectMapper = objectMapper;
    }

    public User updateUser(String id, String requesterUserId, UpdateUserRequest request) {
        if (requesterUserId == null) {
            log.warn("Update rejected: no authenticated requester for target user {}", id);
            throw new ApiException("Unauthorized action", 403);
        }

        User user = userRepository.findById(id)
                .orElseThrow(() -> {
                    log.warn("Update failed: user not found for id {}", id);
                    return new ApiException("User not found", 404);
                });

        if (request.getName() != null) user.setName(request.getName());
        if (request.getEmail() != null) user.setEmail(request.getEmail());

        log.info("User {} updated by requester {}", id, requesterUserId);
        return userRepository.save(user);
    }

    public User fetchUserData(String id) {
        if (!ObjectId.isValid(id)) {
            throw new ApiException("Invalid user ID", 400);
        }

        String cacheKey = "user:" + id;
        String cached = redisCacheService.get(cacheKey);
        if (cached != null) {
            try {
                return objectMapper.readValue(cached, User.class);
            } catch (Exception e) {
                log.debug("Cache read/parse failed for user {}, falling back to DB: {}", id, e.getMessage());
            }
        }

        User user = userRepository.findById(id)
                .orElseThrow(() -> new ApiException("User not found", 404));

        try {
            redisCacheService.set(cacheKey, objectMapper.writeValueAsString(user));
        } catch (Exception e) {
            log.debug("Failed to cache user {}: {}", id, e.getMessage());
        }

        return user;
    }
}
