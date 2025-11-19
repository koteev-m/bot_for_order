ALTER TABLE prices_display
    ADD COLUMN IF NOT EXISTS invoice_amount_minor BIGINT;

CREATE INDEX IF NOT EXISTS idx_watch_trigger_item ON watchlist(trigger_type, item_id);
CREATE INDEX IF NOT EXISTS idx_watch_user_trigger ON watchlist(user_id, trigger_type);

UPDATE watchlist SET trigger_type = UPPER(trigger_type);
