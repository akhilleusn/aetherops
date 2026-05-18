-- M6: AI analysis results, one row per analysis run (append-only)

CREATE TABLE ai_analyses (
    id                      UUID             PRIMARY KEY DEFAULT gen_random_uuid(),
    incident_id             UUID             NOT NULL,
    tenant_id               UUID             NOT NULL,
    summary                 TEXT,
    likely_cause            TEXT,
    confidence_score        DOUBLE PRECISION NOT NULL,   -- decimal 0.0–1.0
    severity                VARCHAR(20),
    evidence_refs           TEXT,                        -- JSON array of evidence item ID strings
    unknowns                TEXT,                        -- JSON array of strings
    recommended_next_checks TEXT,                        -- JSON array of strings
    model                   VARCHAR(100),
    prompt_version          VARCHAR(20),
    created_at              TIMESTAMPTZ      NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_ai_analyses_incident_id      ON ai_analyses (incident_id);
CREATE INDEX idx_ai_analyses_tenant_id        ON ai_analyses (tenant_id);
CREATE INDEX idx_ai_analyses_incident_created ON ai_analyses (incident_id, created_at DESC);
