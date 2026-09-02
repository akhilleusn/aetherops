package com.aetherops.auth.security;

import java.util.UUID;

/**
 * Request-scoped holder for the authenticated user's id, extracted from the JWT by
 * {@link CurrentUserContextFilter}. Mirrors {@code com.aetherops.tenant.TenantContext} —
 * same ThreadLocal-per-request pattern, cleared in the filter's {@code finally} block so a
 * pooled request-handling thread never leaks one request's identity into the next.
 *
 * <p>Controllers that expose a user-scoped resource under a {@code {userId}} path variable
 * (e.g. calendar OAuth connections) must compare it against {@link #get()} — nothing upstream
 * (JwtAuthFilter, TenantContextFilter) ties the path segment to the caller's own identity.
 */
public final class CurrentUserContext {

    private static final ThreadLocal<UUID> USER_ID = new ThreadLocal<>();

    private CurrentUserContext() {}

    public static void set(UUID userId) {
        USER_ID.set(userId);
    }

    public static UUID get() {
        return USER_ID.get();
    }

    public static void clear() {
        USER_ID.remove();
    }
}
