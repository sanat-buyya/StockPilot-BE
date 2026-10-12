package com.stockpilot.config;

import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "stockpilot")
public record AppProperties(Jwt jwt, Cors cors, int bcryptStrength, RateLimit rateLimit, Seed seed) {

    public record Jwt(String secret, long expiresMinutes) {}

    public record Cors(List<String> allowedOrigins) {}

    public record RateLimit(int authPerMinute) {}

    public record Seed(boolean enabled, String password) {}
}
