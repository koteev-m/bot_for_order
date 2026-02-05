CREATE TABLE IF NOT EXISTS telegram_webhook_dedup (
    bot_type VARCHAR(32) NOT NULL,
    update_id BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (bot_type, update_id)
);

CREATE INDEX IF NOT EXISTS idx_telegram_webhook_dedup_created_at
    ON telegram_webhook_dedup (created_at);
