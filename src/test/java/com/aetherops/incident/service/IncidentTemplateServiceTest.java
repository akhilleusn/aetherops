package com.aetherops.incident.service;

import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.incident.model.CreateIncidentTemplateRequest;
import com.aetherops.incident.model.IncidentSeverity;
import com.aetherops.incident.model.IncidentTemplate;
import com.aetherops.incident.model.IncidentTemplateResponse;
import com.aetherops.incident.repository.IncidentTemplateRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class IncidentTemplateServiceTest {

    @Mock private IncidentTemplateRepository templateRepository;

    @InjectMocks
    private IncidentTemplateService templateService;

    @Test
    void create_savesTenantScopedTemplate() {
        UUID tenantId = UUID.randomUUID();
        CreateIncidentTemplateRequest request = new CreateIncidentTemplateRequest(
            "Database Down", "Database unavailable", "desc", IncidentSeverity.CRITICAL, "db-svc",
            Map.of("category", "database"));

        when(templateRepository.save(any())).thenAnswer(i -> {
            IncidentTemplate t = i.getArgument(0);
            t.setId(UUID.randomUUID());
            return t;
        });

        IncidentTemplateResponse response = templateService.create(tenantId, request);

        assertThat(response.getTenantId()).isEqualTo(tenantId);
        assertThat(response.getName()).isEqualTo("Database Down");
        assertThat(response.getDefaultSeverity()).isEqualTo(IncidentSeverity.CRITICAL);
        assertThat(response.getDefaultTags()).containsEntry("category", "database");
    }

    @Test
    void list_returnsGlobalAndTenantTemplates() {
        UUID tenantId = UUID.randomUUID();
        IncidentTemplate global = IncidentTemplate.builder()
            .id(UUID.randomUUID()).tenantId(null).name("API Latency").build();
        IncidentTemplate tenantSpecific = IncidentTemplate.builder()
            .id(UUID.randomUUID()).tenantId(tenantId).name("Custom Template").build();

        when(templateRepository.findByTenantIdIsNullOrTenantIdOrderByName(tenantId))
            .thenReturn(List.of(global, tenantSpecific));

        List<IncidentTemplateResponse> result = templateService.list(tenantId);

        assertThat(result).hasSize(2);
        assertThat(result).extracting(IncidentTemplateResponse::getName)
            .containsExactly("API Latency", "Custom Template");
    }

    @Test
    void findVisible_delegatesToRepositoryVisibilityQuery() {
        UUID templateId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        IncidentTemplate template = IncidentTemplate.builder().id(templateId).tenantId(null).name("Global").build();

        when(templateRepository.findVisibleToTenant(templateId, tenantId)).thenReturn(Optional.of(template));

        IncidentTemplate result = templateService.findVisible(templateId, tenantId);

        assertThat(result.getId()).isEqualTo(templateId);
    }

    @Test
    void findVisible_notVisibleToTenant_throwsResourceNotFound() {
        UUID templateId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        when(templateRepository.findVisibleToTenant(templateId, tenantId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> templateService.findVisible(templateId, tenantId))
            .isInstanceOf(ResourceNotFoundException.class);
    }
}
