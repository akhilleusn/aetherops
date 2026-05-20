package com.aetherops.ingestion.model;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class LogEventRequest {

    private UUID tenantId;

    private UUID projectId;

    @NotBlank(message = "serviceId is required")
    @Size(min = 2, max = 100, message = "serviceId must be between 2 and 100 characters")
    private String serviceId;

    @Size(max = 50)
    private String environment;

    @NotNull(message = "level is required")
    private LogLevel level;

    @NotBlank(message = "message is required")
    private String message;

    @Size(max = 128)
    private String traceId;

    @Size(max = 128)
    private String spanId;

    @NotNull(message = "occurredAt is required")
    private Instant occurredAt;

    private String rawPayload;
}
