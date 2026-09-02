package com.aetherops.tenant.service;

import com.aetherops.auth.repository.UserRepository;
import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.incident.repository.IncidentRepository;
import com.aetherops.slack.SlackWorkspaceRepository;
import com.aetherops.tenant.model.OnboardingStatusResponse;
import com.aetherops.tenant.repository.TenantRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * Backs the onboarding-wizard contract: reports which step of "create admin -> connect
 * Slack -> see a first incident" a tenant has reached, so a frontend wizard has a real
 * status to drive off of instead of guessing.
 *
 * Monitoring-tool connection (Datadog, Prometheus, ...) isn't tracked here: those
 * integrations are stateless inbound webhook receivers with no per-tenant "configured"
 * record in the schema today, so there's nothing truthful to report for that step yet.
 */
@Service
@RequiredArgsConstructor
public class OnboardingStatusService {

    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final SlackWorkspaceRepository slackWorkspaceRepository;
    private final IncidentRepository incidentRepository;

    @Transactional(readOnly = true)
    public OnboardingStatusResponse getStatus(UUID tenantId) {
        if (!tenantRepository.existsById(tenantId)) {
            throw new ResourceNotFoundException("Tenant not found: " + tenantId);
        }

        boolean adminUserCreated = userRepository.existsByTenantId(tenantId);
        boolean slackConnected = slackWorkspaceRepository.findByTenantId(tenantId).isPresent();
        boolean firstIncidentSeen = incidentRepository.existsByTenantId(tenantId);
        boolean complete = adminUserCreated && slackConnected && firstIncidentSeen;

        String nextStep;
        if (!adminUserCreated) nextStep = "CREATE_ADMIN_USER";
        else if (!slackConnected) nextStep = "CONNECT_SLACK";
        else if (!firstIncidentSeen) nextStep = "SEE_FIRST_INCIDENT";
        else nextStep = null;

        return OnboardingStatusResponse.builder()
            .tenantId(tenantId)
            .adminUserCreated(adminUserCreated)
            .slackConnected(slackConnected)
            .firstIncidentSeen(firstIncidentSeen)
            .onboardingComplete(complete)
            .nextStep(nextStep)
            .steps(List.of("CREATE_ADMIN_USER", "CONNECT_SLACK", "SEE_FIRST_INCIDENT"))
            .build();
    }
}
