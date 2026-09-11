-- Step G1 (see STEP_REPORT.md): escalation_executions previously recorded that an escalation
-- step ran, with no signal at all about whether any notification channel actually reached
-- anyone. A page could fail (or never even be attempted -- Twilio was never initialized) and
-- this table, the incident timeline, and every audit log all reported success.
--
-- Two-tier per channel: a coarse status for quick querying ("every escalation where
-- sms_status = 'FAILED'"), plus a free-text detail column carrying the specific reason
-- (PageOutcome name, provider error message, "no rotation configured", etc.) for whoever is
-- actually debugging why a page didn't go out. NOT_ATTEMPTED is the default for existing rows
-- (and for any channel skipped after an earlier channel in the fallback chain already
-- succeeded) -- not a fabricated ACCEPTED, since we don't know what happened before this
-- migration, and NOT_ATTEMPTED != FAILED (a channel that was never tried was never tried).
ALTER TABLE escalation_executions
    ADD COLUMN sms_status   VARCHAR(20) NOT NULL DEFAULT 'NOT_ATTEMPTED',
    ADD COLUMN sms_detail   TEXT,
    ADD COLUMN push_status  VARCHAR(20) NOT NULL DEFAULT 'NOT_ATTEMPTED',
    ADD COLUMN push_detail  TEXT,
    ADD COLUMN slack_status VARCHAR(20) NOT NULL DEFAULT 'NOT_ATTEMPTED',
    ADD COLUMN slack_detail TEXT;
