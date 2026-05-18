-- M8B: postmortem drafts, one row per generation run (append-only)

CREATE TABLE postmortem_drafts (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id         UUID         NOT NULL,
    tenant_id           UUID         NOT NULL,
    draft_version       INTEGER      NOT NULL,
    evidence_count      INTEGER      NOT NULL DEFAULT 0,
    analysis_enriched   BOOLEAN      NOT NULL DEFAULT FALSE,
    incident_summary    TEXT,
    timeline            TEXT,                        -- JSON array of TimelineEntry objects
    likely_cause        TEXT,
    impact              TEXT,
    data_gaps           TEXT,                        -- JSON array of strings
    follow_up_actions   TEXT,                        -- JSON array of strings
    generator_version   VARCHAR(20),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_postmortem_drafts_incident_id      ON postmortem_drafts (incident_id);
CREATE INDEX idx_postmortem_drafts_tenant_id        ON postmortem_drafts (tenant_id);
CREATE INDEX idx_postmortem_drafts_incident_created ON postmortem_drafts (incident_id, created_at DESC);
