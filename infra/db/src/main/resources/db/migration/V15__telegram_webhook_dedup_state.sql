ALTER TABLE telegram_webhook_dedup
    ADD COLUMN IF NOT EXISTS processed_at TIMESTAMPTZ NULL;

CREATE INDEX IF NOT EXISTS idx_telegram_webhook_dedup_processed_created
    ON telegram_webhook_dedup (processed_at, created_at);
