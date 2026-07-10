package com.flicklog.user.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.flicklog.common.cache.RedisCacheService;
import com.flicklog.user.dto.UpdateUserRequest;
import com.flicklog.common.exception.ApiException;
import com.flicklog.user.model.User;
import com.flicklog.user.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private RedisCacheService redisCacheService;
    @Mock private ObjectMapper objectMapper;

    @InjectMocks private UserService userService;

    private static final String VALID_ID = "507f1f77bcf86cd799439011";

    private User existingUser;

    @BeforeEach
    void setUp() {
        existingUser = new User();
        existingUser.setId(VALID_ID);
        existingUser.setName("Jane Doe");
        existingUser.setEmail("jane@example.com");
    }

    // --- updateUser ---

    @Test
    void updateUser_withNullRequester_throws403() {
        UpdateUserRequest request = new UpdateUserRequest();
        request.setName("New Name");

        assertThatThrownBy(() -> userService.updateUser(VALID_ID, null, request))
                .isInstanceOf(ApiException.class)
                .hasMessage("Unauthorized action");

        verifyNoInteractions(userRepository);
    }

    @Test
    void updateUser_withUnknownId_throws404() {
        UpdateUserRequest request = new UpdateUserRequest();
        request.setName("New Name");

        when(userRepository.findById(VALID_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.updateUser(VALID_ID, VALID_ID, request))
                .isInstanceOf(ApiException.class)
                .hasMessage("User not found");
    }

    @Test
    void updateUser_withValidRequest_updatesNameAndEmail() {
        UpdateUserRequest request = new UpdateUserRequest();
        request.setName("Updated Name");
        request.setEmail("updated@example.com");

        when(userRepository.findById(VALID_ID)).thenReturn(Optional.of(existingUser));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        User result = userService.updateUser(VALID_ID, VALID_ID, request);

        assertThat(result.getName()).isEqualTo("Updated Name");
        assertThat(result.getEmail()).isEqualTo("updated@example.com");
        verify(userRepository).save(existingUser);
    }

    // --- fetchUserData ---

    @Test
    void fetchUserData_withInvalidId_throws400() {
        assertThatThrownBy(() -> userService.fetchUserData("not-a-valid-id"))
                .isInstanceOf(ApiException.class)
                .hasMessage("Invalid user ID");

        verifyNoInteractions(userRepository, redisCacheService);
    }

    @Test
    void fetchUserData_cacheHit_returnsDeserializedUserWithoutHittingRepository() throws Exception {
        when(redisCacheService.get("user:" + VALID_ID)).thenReturn("{\"cached\":true}");
        when(objectMapper.readValue("{\"cached\":true}", User.class)).thenReturn(existingUser);

        User result = userService.fetchUserData(VALID_ID);

        assertThat(result).isEqualTo(existingUser);
        verifyNoInteractions(userRepository);
    }

    @Test
    void fetchUserData_cacheMiss_fallsThroughToRepositoryAndCaches() throws Exception {
        when(redisCacheService.get("user:" + VALID_ID)).thenReturn(null);
        when(userRepository.findById(VALID_ID)).thenReturn(Optional.of(existingUser));
        when(objectMapper.writeValueAsString(existingUser)).thenReturn("{\"serialized\":true}");

        User result = userService.fetchUserData(VALID_ID);

        assertThat(result).isEqualTo(existingUser);
        verify(redisCacheService).set("user:" + VALID_ID, "{\"serialized\":true}");
    }

    @Test
    void fetchUserData_notFound_throws404() {
        when(redisCacheService.get("user:" + VALID_ID)).thenReturn(null);
        when(userRepository.findById(VALID_ID)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> userService.fetchUserData(VALID_ID))
                .isInstanceOf(ApiException.class)
                .hasMessage("User not found");
    }

    // Regression test for the bug we fixed: a cache-write failure must NOT
    // turn a successful DB lookup into a 500 for the caller.
    @Test
    void fetchUserData_cacheWriteFailure_stillReturnsUser() throws Exception {
        when(redisCacheService.get("user:" + VALID_ID)).thenReturn(null);
        when(userRepository.findById(VALID_ID)).thenReturn(Optional.of(existingUser));
        when(objectMapper.writeValueAsString(existingUser))
                .thenThrow(new com.fasterxml.jackson.core.JsonProcessingException("boom") {});

        User result = userService.fetchUserData(VALID_ID);

        assertThat(result).isEqualTo(existingUser);
    }
}