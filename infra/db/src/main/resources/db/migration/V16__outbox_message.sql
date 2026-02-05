CREATE TABLE IF NOT EXISTS outbox_message (
    id BIGSERIAL PRIMARY KEY,
    type TEXT NOT NULL,
    payload_json TEXT NOT NULL,
    status TEXT NOT NULL,
    attempts INT NOT NULL DEFAULT 0,
    next_attempt_at TIMESTAMPTZ NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    last_error TEXT NULL
);

CREATE INDEX IF NOT EXISTS idx_outbox_message_status_next_attempt_at
    ON outbox_message (status, next_attempt_at);

CREATE INDEX IF NOT EXISTS idx_outbox_message_created_at
    ON outbox_message (created_at);
