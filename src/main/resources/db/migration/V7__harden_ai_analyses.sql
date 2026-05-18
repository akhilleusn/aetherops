-- M6.5: add analysis versioning and evidence count to ai_analyses

ALTER TABLE ai_analyses
    ADD COLUMN analysis_version INTEGER NOT NULL DEFAULT 1,
    ADD COLUMN evidence_count   INTEGER NOT NULL DEFAULT 0;
