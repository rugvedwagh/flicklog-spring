package com.flicklog.user.repository;

import com.flicklog.user.model.User;
import org.springframework.data.mongodb.repository.MongoRepository;

import java.util.Optional;

public interface UserRepository extends MongoRepository<User, String> {

    Optional<User> findByEmail(String email);

    boolean existsByEmail(String email);

    // Backs the csrf middleware's User.findOne({ "sessions.sessionId": sessionId })
    Optional<User> findBySessions_SessionId(String sessionId);
}
