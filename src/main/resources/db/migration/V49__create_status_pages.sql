CREATE TABLE status_pages (
    id          UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    tenant_id   UUID         NOT NULL,
    slug        VARCHAR(63)  NOT NULL,
    name        VARCHAR(200) NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX idx_status_pages_tenant ON status_pages (tenant_id);
CREATE UNIQUE INDEX idx_status_pages_slug ON status_pages (slug);

CREATE TABLE status_page_incident_updates (
    id           UUID        NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    incident_id  UUID        NOT NULL,
    tenant_id    UUID        NOT NULL,
    status       VARCHAR(20) NOT NULL,
    message      TEXT        NOT NULL,
    posted_at    TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_status_page_updates_incident ON status_page_incident_updates (incident_id, posted_at);
CREATE INDEX idx_status_page_updates_tenant ON status_page_incident_updates (tenant_id, posted_at DESC);
