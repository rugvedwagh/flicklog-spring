package com.flicklog.common;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

@Data
@Configuration
@ConfigurationProperties(prefix = "app.jwt")
public class JwtProperties {
    private String accessSecret;
    private String refreshSecret;
    private long accessExpiryMinutes;
    private long refreshExpiryDays;
}
