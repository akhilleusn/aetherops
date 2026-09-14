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
public class CreateApiKeyResponse {

    private UUID id;
    private UUID projectId;
    private String keyPrefix;
    private String plaintext;
    private String description;
    private Instant createdAt;

    @Override
    public String toString() {
        return "CreateApiKeyResponse(id=" + id + ", projectId=" + projectId + ", keyPrefix=" + keyPrefix
            + ", plaintext=[REDACTED], description=" + description + ", createdAt=" + createdAt + ")";
    }
}
