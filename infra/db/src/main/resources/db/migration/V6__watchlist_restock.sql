DROP INDEX IF EXISTS idx_watch_trigger_item;
CREATE INDEX IF NOT EXISTS idx_watch_trigger_item_variant
    ON watchlist (trigger_type, item_id, variant_id);
CREATE INDEX IF NOT EXISTS idx_watch_user_trigger
    ON watchlist (user_id, trigger_type);
