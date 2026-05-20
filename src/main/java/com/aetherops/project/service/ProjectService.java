package com.aetherops.project.service;

import com.aetherops.common.exception.ResourceNotFoundException;
import com.aetherops.project.model.CreateProjectRequest;
import com.aetherops.project.model.Project;
import com.aetherops.project.repository.ProjectRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class ProjectService {

    private final ProjectRepository projectRepository;

    @Transactional
    public Project create(CreateProjectRequest request) {
        Project project = Project.builder()
            .tenantId(request.getTenantId())
            .name(request.getName())
            .build();
        Project saved = projectRepository.save(project);
        log.info("Created project id={} tenant={}", saved.getId(), saved.getTenantId());
        return saved;
    }

    @Transactional(readOnly = true)
    public List<Project> listByTenant(UUID tenantId) {
        return projectRepository.findByTenantId(tenantId);
    }

    @Transactional(readOnly = true)
    public Project getForTenant(UUID projectId, UUID tenantId) {
        return projectRepository.findById(projectId)
            .filter(p -> tenantId.equals(p.getTenantId()))
            .orElseThrow(() -> new ResourceNotFoundException("Project not found: " + projectId));
    }
}
