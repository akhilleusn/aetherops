package com.aetherops.auth.security;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class CurrentUserContextTest {

    @AfterEach
    void cleanup() {
        CurrentUserContext.clear();
    }

    @Test
    void setAndGet_returnsSameValue() {
        UUID userId = UUID.randomUUID();
        CurrentUserContext.set(userId);
        assertThat(CurrentUserContext.get()).isEqualTo(userId);
    }

    @Test
    void clear_removesValue() {
        CurrentUserContext.set(UUID.randomUUID());
        CurrentUserContext.clear();
        assertThat(CurrentUserContext.get()).isNull();
    }

    @Test
    void getBeforeSet_returnsNull() {
        assertThat(CurrentUserContext.get()).isNull();
    }

    @Test
    void set_overwritesPreviousValue() {
        UUID first = UUID.randomUUID();
        UUID second = UUID.randomUUID();
        CurrentUserContext.set(first);
        CurrentUserContext.set(second);
        assertThat(CurrentUserContext.get()).isEqualTo(second);
    }
}
