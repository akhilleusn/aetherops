CREATE TABLE saga_steps (
    id          UUID         NOT NULL DEFAULT gen_random_uuid() PRIMARY KEY,
    saga_id     UUID         NOT NULL,
    step_name   VARCHAR(100) NOT NULL,
    status      VARCHAR(20)  NOT NULL,
    detail      TEXT,
    recorded_at TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_saga_steps_saga_id ON saga_steps (saga_id);
