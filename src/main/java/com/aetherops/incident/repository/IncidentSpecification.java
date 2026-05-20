package com.aetherops.incident.repository;

import com.aetherops.incident.model.Incident;
import com.aetherops.incident.model.IncidentSeverity;
import com.aetherops.incident.model.IncidentStatus;
import org.springframework.data.jpa.domain.Specification;

import java.util.UUID;

public final class IncidentSpecification {

    private IncidentSpecification() {}

    public static Specification<Incident> hasTenant(UUID tenantId) {
        return (root, query, cb) -> cb.equal(root.get("tenantId"), tenantId);
    }

    public static Specification<Incident> hasStatus(IncidentStatus status) {
        return (root, query, cb) -> status == null
            ? cb.conjunction()
            : cb.equal(root.get("status"), status);
    }

    public static Specification<Incident> hasServiceId(String serviceId) {
        return (root, query, cb) -> serviceId == null
            ? cb.conjunction()
            : cb.equal(root.get("serviceId"), serviceId);
    }

    public static Specification<Incident> hasSeverity(IncidentSeverity severity) {
        return (root, query, cb) -> severity == null
            ? cb.conjunction()
            : cb.equal(root.get("severity"), severity);
    }

    public static Specification<Incident> hasProjectId(UUID projectId) {
        return (root, query, cb) -> projectId == null
            ? cb.conjunction()
            : cb.equal(root.get("projectId"), projectId);
    }
}
