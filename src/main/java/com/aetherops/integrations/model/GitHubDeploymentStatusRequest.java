package com.aetherops.integrations.model;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * GitHub "deployment_status" webhook event payload (subset used for deploy-failure alerting).
 *
 * <p>Phase 2 remediation, finding 3 (see STEP_REPORT.md): {@code repository.full_name} is the
 * source of {@code Incident.serviceId} (VARCHAR(100) NOT NULL) and {@code deployment.environment}
 * is the source of {@code Incident.environment} (VARCHAR(50)) — both bounded here so an oversized
 * value is rejected (400). {@code IntegrationsWebhookController} builds its title/alertName
 * strings only from these already-bounded fields plus fixed literal text, so title cannot exceed
 * Incident.title's 500-char limit and needs no separate truncation.
 */
@Data
@NoArgsConstructor
public class GitHubDeploymentStatusRequest {

    private String action;
    private DeploymentStatus deployment_status;
    @Valid
    private Deployment deployment;
    @Valid
    private Repository repository;

    @Data
    @NoArgsConstructor
    public static class DeploymentStatus {
        /** failure, error, success, pending, in_progress, queued. */
        private String state;
    }

    @Data
    @NoArgsConstructor
    public static class Deployment {
        @Size(max = 50, message = "deployment.environment must not exceed 50 characters")
        private String environment;
        private String task;
        private String sha;
    }

    @Data
    @NoArgsConstructor
    public static class Repository {
        @Size(max = 100, message = "repository.full_name must not exceed 100 characters")
        private String full_name;
    }
}
