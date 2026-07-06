package com.flicklog.controller;

import com.flicklog.dto.request.UpdateUserRequest;
import com.flicklog.model.User;
import com.flicklog.service.UserService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.bind.annotation.*;

/**
 * Mirrors routes/user.routes.js + controllers/user.controllers.js.
 */
@RestController
@RequestMapping("/user")
public class UserController {

    private final UserService userService;

    public UserController(UserService userService) {
        this.userService = userService;
    }

    @PatchMapping("/{id}/update")
    public User updateUser(@PathVariable String id, @RequestBody UpdateUserRequest request, HttpServletRequest httpRequest) {
        String requesterUserId = (String) httpRequest.getAttribute("userId");
        return userService.updateUser(id, requesterUserId, request);
    }

    @GetMapping("/account/{id}")
    public User fetchUserData(@PathVariable String id) {
        return userService.fetchUserData(id);
    }
}
