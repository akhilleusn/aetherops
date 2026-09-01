package com.aetherops.integrations.model;

import lombok.Data;
import lombok.NoArgsConstructor;

/** GitHub "deployment_status" webhook event payload (subset used for deploy-failure alerting). */
@Data
@NoArgsConstructor
public class GitHubDeploymentStatusRequest {

    private String action;
    private DeploymentStatus deployment_status;
    private Deployment deployment;
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
        private String environment;
        private String task;
        private String sha;
    }

    @Data
    @NoArgsConstructor
    public static class Repository {
        private String full_name;
    }
}
