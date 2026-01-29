CREATE TABLE IF NOT EXISTS audit_log (
    id BIGSERIAL PRIMARY KEY,
    admin_user_id BIGINT NOT NULL,
    action TEXT NOT NULL,
    order_id VARCHAR(64) NULL,
    payload_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ip TEXT NULL,
    user_agent TEXT NULL
);

CREATE INDEX IF NOT EXISTS idx_audit_log_admin_user_created
    ON audit_log (admin_user_id, created_at DESC);

CREATE INDEX IF NOT EXISTS idx_audit_log_order_id
    ON audit_log (order_id);

CREATE TABLE IF NOT EXISTS event_log (
    id BIGSERIAL PRIMARY KEY,
    ts TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    event_type TEXT NOT NULL,
    buyer_user_id BIGINT NULL,
    merchant_id VARCHAR(64) NOT NULL REFERENCES merchants(id) ON DELETE CASCADE,
    storefront_id VARCHAR(64) NULL,
    channel_id BIGINT NULL,
    post_message_id INT NULL,
    listing_id VARCHAR(64) NULL,
    variant_id VARCHAR(64) NULL,
    metadata_json TEXT NULL
);

CREATE INDEX IF NOT EXISTS idx_event_log_merchant_ts
    ON event_log (merchant_id, ts DESC);

CREATE INDEX IF NOT EXISTS idx_event_log_type_ts
    ON event_log (event_type, ts DESC);

CREATE INDEX IF NOT EXISTS idx_event_log_buyer_ts
    ON event_log (buyer_user_id, ts DESC);

CREATE TABLE IF NOT EXISTS idempotency_key (
    merchant_id VARCHAR(64) NOT NULL REFERENCES merchants(id) ON DELETE CASCADE,
    user_id BIGINT NOT NULL,
    scope VARCHAR(64) NOT NULL,
    key VARCHAR(128) NOT NULL,
    request_hash CHAR(64) NOT NULL,
    response_status INT NULL,
    response_json TEXT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (merchant_id, user_id, scope, key)
);

CREATE INDEX IF NOT EXISTS idx_idempotency_scope
    ON idempotency_key (merchant_id, user_id, scope);
