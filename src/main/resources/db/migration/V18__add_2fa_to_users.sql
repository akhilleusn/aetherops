ALTER TABLE users
    ADD COLUMN totp_secret    VARCHAR(512),
    ADD COLUMN two_fa_enabled BOOLEAN NOT NULL DEFAULT FALSE;

CREATE TABLE totp_backup_codes (
    id         UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id    UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    code_hash  VARCHAR(64)  NOT NULL,
    used       BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);
CREATE INDEX idx_tbc_user_id ON totp_backup_codes(user_id);
