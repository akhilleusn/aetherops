CREATE TABLE incident_templates (
    id                   UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id            UUID,                          -- NULL = global system template
    name                 VARCHAR(100) NOT NULL,
    default_title        VARCHAR(500),
    default_description  TEXT,
    default_severity     VARCHAR(20),
    default_service_id   VARCHAR(100),
    default_tags         TEXT,                          -- JSON object of string key/value tags
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_incident_templates_tenant ON incident_templates (tenant_id);

-- Global default templates, visible to every tenant (tenant_id IS NULL).
INSERT INTO incident_templates (tenant_id, name, default_title, default_description, default_severity, default_tags) VALUES
    (NULL, 'Database Down', 'Database unavailable', 'The primary database is not accepting connections.', 'CRITICAL', '{"category":"database"}'),
    (NULL, 'API Latency', 'Elevated API latency', 'API response times are significantly above baseline.', 'HIGH', '{"category":"performance"}'),
    (NULL, 'Deployment Failure', 'Deployment failed', 'A deployment failed to roll out or is causing errors post-deploy.', 'HIGH', '{"category":"deployment"}'),
    (NULL, 'Third-Party Outage', 'Third-party dependency outage', 'An upstream vendor or third-party API is unavailable or degraded.', 'MEDIUM', '{"category":"dependency"}');
