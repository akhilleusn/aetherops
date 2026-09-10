package com.aetherops.ratelimit;

import com.aetherops.tenant.model.TenantResponse;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.serializer.Jackson2JsonRedisSerializer;
import org.springframework.data.redis.serializer.StringRedisSerializer;

@Configuration
public class RedisConfig {

    @Bean
    public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
        return new StringRedisTemplate(connectionFactory);
    }

    /**
     * Phase 2 remediation, finding 9 (see STEP_REPORT.md): this used to be a general-purpose
     * {@code RedisTemplate<String, Object>} backed by {@code GenericJackson2JsonRedisSerializer},
     * which activates Jackson polymorphic typing (embeds a {@code @class} field per value so it
     * can deserialize back to arbitrary types) purely so one caller could cache one type.
     * {@link com.aetherops.tenant.service.TenantService} is the only consumer (confirmed by
     * grepping every {@code redisTemplate} reference in the codebase — everything else uses the
     * separate {@code StringRedisTemplate} bean above) and caches exactly one type,
     * {@link TenantResponse}, so this is now scoped to it directly: no polymorphic typing, no
     * {@code @class} metadata, nothing to deserialize except the one type it's declared for.
     */
    @Bean
    public RedisTemplate<String, TenantResponse> tenantCacheRedisTemplate(RedisConnectionFactory connectionFactory) {
        ObjectMapper mapper = new ObjectMapper();
        mapper.registerModule(new JavaTimeModule());
        mapper.disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

        RedisTemplate<String, TenantResponse> template = new RedisTemplate<>();
        template.setConnectionFactory(connectionFactory);
        template.setKeySerializer(new StringRedisSerializer());
        template.setValueSerializer(new Jackson2JsonRedisSerializer<>(mapper, TenantResponse.class));
        return template;
    }
}