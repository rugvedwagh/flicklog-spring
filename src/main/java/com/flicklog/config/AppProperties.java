package com.flicklog.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private final Cors cors = new Cors();
    private final Cloudinary cloudinary = new Cloudinary();
    private final Cache cache = new Cache();
    private final Cookie cookie = new Cookie();

    @Data
    public static class Cors {
        private String allowedOrigin;
    }

    @Data
    public static class Cloudinary {
        private String cloudName;
        private String apiKey;
        private String apiSecret;
    }

    @Data
    public static class Cache {
        private long expirySeconds = 300;
    }

    @Data
    public static class Cookie {
        private String domain = "";
    }
}