-- RAG-lite: resolution notes on incidents + vector embeddings for similar-incident retrieval

ALTER TABLE incidents ADD COLUMN resolution_notes TEXT;

ALTER TABLE ai_analyses ADD COLUMN similar_incident_ids TEXT;

CREATE TABLE incident_embeddings (
    incident_id      UUID         NOT NULL PRIMARY KEY REFERENCES incidents(id) ON DELETE CASCADE,
    tenant_id        UUID         NOT NULL,
    embedding        TEXT         NOT NULL,
    indexed_text     TEXT         NOT NULL,
    embedding_model  VARCHAR(100) NOT NULL DEFAULT 'local-tfidf-v1',
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_incident_embeddings_tenant ON incident_embeddings (tenant_id);
