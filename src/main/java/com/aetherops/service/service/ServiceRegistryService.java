package com.aetherops.service.service;

import com.aetherops.project.model.Project;
import com.aetherops.project.service.ProjectService;
import com.aetherops.service.model.CreateServiceRequest;
import com.aetherops.service.model.Service;
import com.aetherops.service.model.ServiceEnvironment;
import com.aetherops.service.repository.ServiceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class ServiceRegistryService {

    private final ServiceRepository serviceRepository;
    private final ProjectService    projectService;

    @Transactional
    public Service create(UUID projectId, UUID tenantId, CreateServiceRequest request) {
        Project project = projectService.getForTenant(projectId, tenantId);
        ServiceEnvironment env = ServiceEnvironment.valueOf(request.getEnvironment().toUpperCase());

        Service svc = Service.builder()
            .tenantId(project.getTenantId())
            .projectId(project.getId())
            .name(request.getName())
            .environment(env)
            .ownerTeam(request.getOwnerTeam())
            .build();

        Service saved = serviceRepository.save(svc);
        log.info("Created service id={} name={} project={} tenant={}",
            saved.getId(), saved.getName(), projectId, tenantId);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Service> listByProject(UUID projectId, UUID tenantId) {
        projectService.getForTenant(projectId, tenantId);
        return serviceRepository.findByTenantIdAndProjectId(tenantId, projectId);
    }

    @Transactional
    public Service findOrRegister(UUID tenantId, UUID projectId, String name, String rawEnvironment) {
        return serviceRepository.findByTenantIdAndProjectIdAndName(tenantId, projectId, name)
            .orElseGet(() -> {
                Service svc = Service.builder()
                    .tenantId(tenantId)
                    .projectId(projectId)
                    .name(name)
                    .environment(parseEnvironment(rawEnvironment))
                    .build();
                Service saved = serviceRepository.saveAndFlush(svc);
                log.info("Auto-registered service name={} project={} tenant={}", name, projectId, tenantId);
                return saved;
            });
    }

    private ServiceEnvironment parseEnvironment(String raw) {
        if (raw == null) return ServiceEnvironment.PRODUCTION;
        try {
            return ServiceEnvironment.valueOf(raw.toUpperCase());
        } catch (IllegalArgumentException e) {
            return ServiceEnvironment.PRODUCTION;
        }
    }
}
