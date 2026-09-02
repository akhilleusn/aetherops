package com.aetherops.tenant.model;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * Progress through the "Slack -> monitoring -> first incident" onboarding path, for a
 * frontend wizard to drive off of. Steps are ordered; {@code nextStep} is the first
 * incomplete one, or null once every step is done.
 */
@Data
@Builder
public class OnboardingStatusResponse {

    private UUID tenantId;
    private boolean adminUserCreated;
    private boolean slackConnected;
    private boolean firstIncidentSeen;
    private boolean onboardingComplete;
    private String nextStep;
    private List<String> steps;
}
