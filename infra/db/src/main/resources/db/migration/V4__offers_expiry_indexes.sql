CREATE INDEX IF NOT EXISTS idx_offers_expires_at ON offers (expires_at);
CREATE INDEX IF NOT EXISTS idx_offers_item_user_status ON offers (item_id, user_id, status);
