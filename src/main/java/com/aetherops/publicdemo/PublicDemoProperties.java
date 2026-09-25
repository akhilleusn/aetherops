package com.aetherops.publicdemo;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

@Component
public class PublicDemoProperties {

    private final UUID tenantId;          // null = demo disabled
    private final int  requestsPerWindow;
    private final int  windowSeconds;

    public PublicDemoProperties(
            @Value("${app.public-demo.tenant-id:}") String tenantId,
            @Value("${app.public-demo.requests-per-window:60}") int requestsPerWindow,
            @Value("${app.public-demo.window-seconds:60}") int windowSeconds) {
        // A malformed id fails startup rather than serving a demo pinned to nothing.
        this.tenantId          = tenantId == null || tenantId.isBlank() ? null : UUID.fromString(tenantId.trim());
        this.requestsPerWindow = requestsPerWindow;
        this.windowSeconds     = windowSeconds;
    }

    public Optional<UUID> tenantId()   { return Optional.ofNullable(tenantId); }
    public int requestsPerWindow()     { return requestsPerWindow; }
    public int windowSeconds()         { return windowSeconds; }
}
