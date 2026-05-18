-- M8: runbook suggestions, one row per generation run (append-only)

CREATE TABLE runbook_suggestions (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id         UUID         NOT NULL,
    tenant_id           UUID         NOT NULL,
    runbook_version     INTEGER      NOT NULL,
    evidence_count      INTEGER      NOT NULL DEFAULT 0,
    steps               TEXT         NOT NULL,   -- JSON array of RunbookStep objects
    generation_basis    VARCHAR(50),
    generator_version   VARCHAR(20),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_runbook_suggestions_incident_id      ON runbook_suggestions (incident_id);
CREATE INDEX idx_runbook_suggestions_tenant_id        ON runbook_suggestions (tenant_id);
CREATE INDEX idx_runbook_suggestions_incident_created ON runbook_suggestions (incident_id, created_at DESC);
