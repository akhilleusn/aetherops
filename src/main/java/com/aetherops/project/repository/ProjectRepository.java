package com.aetherops.project.repository;

import com.aetherops.project.model.Project;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface ProjectRepository extends JpaRepository<Project, UUID> {
    List<Project> findByTenantId(UUID tenantId);
}
