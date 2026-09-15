package com.gym.management.auth.internal;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.time.Duration;

@ConfigurationProperties("app.auth")
record AuthProperties(
        String issuer,
        Duration accessTokenTtl,
        Duration refreshTokenTtl,
        String jwtSecret
) {
}
