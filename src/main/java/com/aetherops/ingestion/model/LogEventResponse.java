package com.aetherops.ingestion.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class LogEventResponse {

    private UUID id;
    private UUID tenantId;
    private UUID projectId;
    private String serviceId;
    private String environment;
    private LogLevel level;
    private String message;
    private String traceId;
    private String spanId;
    private Instant occurredAt;
    private Instant ingestedAt;
}
