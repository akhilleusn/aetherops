package com.aetherops.apikey.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ApiKeyView {

    private UUID id;
    private UUID projectId;
    private String keyPrefix;
    private String description;
    private boolean active;
    private Instant createdAt;
    private Instant revokedAt;
}
