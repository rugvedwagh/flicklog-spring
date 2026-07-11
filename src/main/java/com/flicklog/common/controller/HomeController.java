package com.flicklog.common.controller;

import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Mirrors the app.get('/', ...) root route in server.js.
 */
@RestController
public class HomeController {

    private final Environment environment;

    public HomeController(Environment environment) {
        this.environment = environment;
    }

    @GetMapping(value = "/", produces = MediaType.TEXT_HTML_VALUE)
    public String home() {
        String[] profiles = environment.getActiveProfiles();
        String activeProfile = profiles.length > 0 ? profiles[0] : "development";

        return """
        <!DOCTYPE html>
        <html lang="en">
        <head>
            <meta charset="UTF-8">
            <meta name="viewport" content="width=device-width, initial-scale=1.0">
            <title>FlickLog API</title>
            <style>
                body {
                    margin: 0;
                    height: 100vh;
                    display: flex;
                    justify-content: center;
                    align-items: center;
                    background: #f5f7fa;
                    font-family: "Segoe UI", Tahoma, Geneva, Verdana, sans-serif;
                }

                .card {
                    background: white;
                    padding: 40px 50px;
                    border-radius: 16px;
                    box-shadow: 0 10px 30px rgba(0, 0, 0, 0.1);
                    text-align: center;
                }

                p {
                    margin: 0;
                    color: #555;
                    font-size: 1.1rem;
                }

                .profile {
                    color: #0d6efd;
                    font-weight: 600;
                    text-transform: capitalize;
                }
            </style>
        </head>
        <body>
            <div class="card">
                <p>The server is running successfully in <span class="profile">%s</span> mode.</p>
            </div>
        </body>
        </html>
        """.formatted(activeProfile);
    }
}
