package com.aetherops.service.service;

import com.aetherops.project.model.Project;
import com.aetherops.project.service.ProjectService;
import com.aetherops.service.model.CreateServiceRequest;
import com.aetherops.service.model.Service;
import com.aetherops.service.repository.ServiceRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ServiceRegistryServiceTest {

    @Mock private ServiceRepository serviceRepository;
    @Mock private ProjectService projectService;

    @InjectMocks
    private ServiceRegistryService registryService;

    @Test
    void create_withOwnerTeam_persistsIt() {
        UUID projectId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        Project project = Project.builder().id(projectId).tenantId(tenantId).build();
        when(projectService.getForTenant(projectId, tenantId)).thenReturn(project);
        when(serviceRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        CreateServiceRequest request = new CreateServiceRequest("payments-api", "PRODUCTION", "payments-team");
        Service saved = registryService.create(projectId, tenantId, request);

        assertThat(saved.getOwnerTeam()).isEqualTo("payments-team");
    }

    @Test
    void create_withoutOwnerTeam_leavesItNull() {
        UUID projectId = UUID.randomUUID();
        UUID tenantId = UUID.randomUUID();
        Project project = Project.builder().id(projectId).tenantId(tenantId).build();
        when(projectService.getForTenant(projectId, tenantId)).thenReturn(project);

        ArgumentCaptor<Service> captor = ArgumentCaptor.forClass(Service.class);
        when(serviceRepository.save(captor.capture())).thenAnswer(i -> i.getArgument(0));

        CreateServiceRequest request = new CreateServiceRequest("payments-api", "PRODUCTION", null);
        registryService.create(projectId, tenantId, request);

        assertThat(captor.getValue().getOwnerTeam()).isNull();
    }
}
