-- Records whether an AI analysis attempt actually completed or failed (e.g. provider call
-- failed, circuit breaker open). Previously a failed attempt left no row at all -- a tenant
-- hitting GET .../analyze/latest afterwards saw the same 404 as if they'd never tried.
ALTER TABLE ai_analyses ADD COLUMN status VARCHAR(20) NOT NULL DEFAULT 'COMPLETED';
ALTER TABLE ai_analyses ADD COLUMN error_message TEXT;
