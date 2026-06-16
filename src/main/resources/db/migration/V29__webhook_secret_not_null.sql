CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- Feature 6: Ensure every webhook endpoint has an HMAC secret
-- Populate NULLs with a random hex secret, then enforce NOT NULL

UPDATE webhook_endpoints
SET secret = encode(gen_random_bytes(32), 'hex')
WHERE secret IS NULL;

ALTER TABLE webhook_endpoints ALTER COLUMN secret SET NOT NULL;
ALTER TABLE webhook_endpoints ALTER COLUMN secret SET DEFAULT encode(gen_random_bytes(32), 'hex');
