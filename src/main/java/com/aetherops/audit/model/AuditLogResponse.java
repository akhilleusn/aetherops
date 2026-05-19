package com.aetherops.audit.model;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
public class AuditLogResponse {

    private UUID id;
    private UUID tenantId;
    private AuditAction action;
    private AuditStatus status;
    private String resourceType;
    private UUID resourceId;
    private String message;
    private Instant createdAt;
}
