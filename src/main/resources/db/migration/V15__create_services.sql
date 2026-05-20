CREATE TABLE services (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id   UUID         NOT NULL,
    project_id  UUID         NOT NULL,
    name        VARCHAR(100) NOT NULL,
    environment VARCHAR(20)  NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE UNIQUE INDEX idx_services_tenant_project_name ON services (tenant_id, project_id, name);
CREATE        INDEX idx_services_project_id           ON services (project_id);
