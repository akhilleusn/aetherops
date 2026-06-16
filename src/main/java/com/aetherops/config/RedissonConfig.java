package com.aetherops.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RedissonConfig {

    // Read from spring.data.redis.* so Redisson and Lettuce share one source
    // of truth. @DynamicPropertySource in tests overrides these with the
    // Testcontainers Redis address; production resolves them from env vars.
    @Value("${spring.data.redis.host:localhost}")
    private String host;

    @Value("${spring.data.redis.port:6379}")
    private int port;

    // Read the password directly from env var rather than via spring.data.redis.password
    // so that an absent env var produces null (no AUTH) rather than empty-string AUTH,
    // which Redis rejects when no password is configured.
    @Value("${REDIS_PASSWORD:${REDISPASSWORD:#{null}}}")
    private String password;

    @Bean(destroyMethod = "shutdown")
    public RedissonClient redissonClient() {
        Config config = new Config();
        config.useSingleServer()
            .setAddress("redis://" + host + ":" + port)
            // Only authenticate when a non-blank password is actually provided.
            // Sending AUTH with an empty string to an unauthenticated Redis
            // returns an error that Redisson surfaces as a connection failure.
            .setPassword((password != null && !password.isBlank()) ? password : null);
        return Redisson.create(config);
    }
}
