package com.flicklog.controller;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mirrors the app.get('/', ...) root route in server.js.
 */
@RestController
public class HomeController {

    @Value("${NODE_ENV:development}")
    private String nodeEnv;

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public String home() {
        return "<h3> Server is running in " + nodeEnv + " mode</h3>";
    }
}
