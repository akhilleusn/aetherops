ALTER TABLE users ADD COLUMN email_verified BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE users ADD COLUMN email_verification_token VARCHAR(64);
ALTER TABLE users ADD COLUMN email_verification_token_expires_at TIMESTAMPTZ;

CREATE UNIQUE INDEX idx_users_email_verification_token
    ON users (email_verification_token)
    WHERE email_verification_token IS NOT NULL;
