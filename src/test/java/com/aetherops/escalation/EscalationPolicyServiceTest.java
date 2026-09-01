package com.aetherops.escalation;

import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.incident.model.IncidentSeverity;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class EscalationPolicyServiceTest {

    @Mock private EscalationPolicyRepository policyRepository;

    @InjectMocks
    private EscalationPolicyService policyService;

    private final UUID tenantId = UUID.randomUUID();

    @Test
    void configure_newPolicy_appliesDefaultsWhenFieldsOmitted() {
        when(policyRepository.findByTenantIdAndServiceId(tenantId, null)).thenReturn(Optional.empty());
        when(policyRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        EscalationPolicyResponse response = policyService.configure(tenantId, new EscalationPolicyRequest(null, null, null, null));

        assertThat(response.getSeverityThreshold()).isEqualTo(IncidentSeverity.CRITICAL);
        assertThat(response.getEscalateAfterMinutes()).isEqualTo(5);
        assertThat(response.isEnabled()).isTrue();
    }

    @Test
    void configure_explicitValues_areUsedVerbatim() {
        when(policyRepository.findByTenantIdAndServiceId(tenantId, "db-svc")).thenReturn(Optional.empty());
        when(policyRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        EscalationPolicyResponse response = policyService.configure(tenantId,
            new EscalationPolicyRequest("db-svc", IncidentSeverity.HIGH, 15, false));

        assertThat(response.getServiceId()).isEqualTo("db-svc");
        assertThat(response.getSeverityThreshold()).isEqualTo(IncidentSeverity.HIGH);
        assertThat(response.getEscalateAfterMinutes()).isEqualTo(15);
        assertThat(response.isEnabled()).isFalse();
    }

    @Test
    void configure_existingPolicy_updatesInPlace() {
        EscalationPolicy existing = EscalationPolicy.builder()
            .id(UUID.randomUUID()).tenantId(tenantId).serviceId("db-svc")
            .severityThreshold(IncidentSeverity.CRITICAL).escalateAfterMinutes(5).enabled(true).build();
        when(policyRepository.findByTenantIdAndServiceId(tenantId, "db-svc")).thenReturn(Optional.of(existing));
        when(policyRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        EscalationPolicyResponse response = policyService.configure(tenantId,
            new EscalationPolicyRequest("db-svc", IncidentSeverity.HIGH, 10, true));

        assertThat(response.getId()).isEqualTo(existing.getId());
        assertThat(response.getEscalateAfterMinutes()).isEqualTo(10);
    }

    @Test
    void delete_wrongTenant_throwsResourceNotFound() {
        UUID policyId = UUID.randomUUID();
        EscalationPolicy policy = EscalationPolicy.builder().id(policyId).tenantId(UUID.randomUUID()).build();
        when(policyRepository.findById(policyId)).thenReturn(Optional.of(policy));

        assertThatThrownBy(() -> policyService.delete(tenantId, policyId))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void delete_unknownPolicy_throwsResourceNotFound() {
        UUID policyId = UUID.randomUUID();
        when(policyRepository.findById(policyId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> policyService.delete(tenantId, policyId))
            .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void list_returnsAllPoliciesForTenant() {
        when(policyRepository.findByTenantId(tenantId)).thenReturn(List.of(
            EscalationPolicy.builder().tenantId(tenantId).serviceId("db-svc").severityThreshold(IncidentSeverity.CRITICAL).escalateAfterMinutes(5).enabled(true).build()));

        assertThat(policyService.list(tenantId)).hasSize(1);
    }

    @Test
    void resolveEffectivePolicy_serviceSpecificOverrideExists_takesPrecedence() {
        EscalationPolicy specific = EscalationPolicy.builder()
            .tenantId(tenantId).serviceId("db-svc").severityThreshold(IncidentSeverity.HIGH).escalateAfterMinutes(2).enabled(true).build();
        when(policyRepository.findByTenantIdAndServiceId(tenantId, "db-svc")).thenReturn(Optional.of(specific));

        EscalationPolicy result = policyService.resolveEffectivePolicy(tenantId, "db-svc");

        assertThat(result.getEscalateAfterMinutes()).isEqualTo(2);
    }

    @Test
    void resolveEffectivePolicy_noServiceOverride_fallsBackToTenantDefault() {
        when(policyRepository.findByTenantIdAndServiceId(tenantId, "db-svc")).thenReturn(Optional.empty());
        EscalationPolicy tenantDefault = EscalationPolicy.builder()
            .tenantId(tenantId).serviceId(null).severityThreshold(IncidentSeverity.CRITICAL).escalateAfterMinutes(20).enabled(true).build();
        when(policyRepository.findByTenantIdAndServiceId(tenantId, null)).thenReturn(Optional.of(tenantDefault));

        EscalationPolicy result = policyService.resolveEffectivePolicy(tenantId, "db-svc");

        assertThat(result.getEscalateAfterMinutes()).isEqualTo(20);
    }

    @Test
    void resolveEffectivePolicy_noPolicyConfiguredAtAll_returnsHardcodedDefault() {
        when(policyRepository.findByTenantIdAndServiceId(any(), any())).thenReturn(Optional.empty());

        EscalationPolicy result = policyService.resolveEffectivePolicy(tenantId, "db-svc");

        assertThat(result.getSeverityThreshold()).isEqualTo(IncidentSeverity.CRITICAL);
        assertThat(result.getEscalateAfterMinutes()).isEqualTo(5);
        assertThat(result.isEnabled()).isTrue();
    }
}
