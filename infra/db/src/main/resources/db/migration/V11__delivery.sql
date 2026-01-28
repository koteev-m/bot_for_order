CREATE TABLE IF NOT EXISTS merchant_delivery_method (
    merchant_id VARCHAR(64) REFERENCES merchants(id) ON DELETE CASCADE,
    type VARCHAR(32) NOT NULL,
    enabled BOOLEAN NOT NULL,
    required_fields_json TEXT NOT NULL DEFAULT '[]',
    PRIMARY KEY (merchant_id, type)
);

CREATE INDEX IF NOT EXISTS idx_merchant_delivery_method_enabled
    ON merchant_delivery_method (merchant_id, enabled);

CREATE TABLE IF NOT EXISTS order_delivery (
    order_id VARCHAR(64) REFERENCES orders(id) ON DELETE CASCADE,
    type VARCHAR(32) NOT NULL,
    fields_json TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (order_id)
);

CREATE INDEX IF NOT EXISTS idx_order_delivery_order
    ON order_delivery (order_id);

CREATE TABLE IF NOT EXISTS buyer_delivery_profile (
    merchant_id VARCHAR(64) REFERENCES merchants(id) ON DELETE CASCADE,
    buyer_user_id BIGINT NOT NULL,
    fields_json TEXT NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    PRIMARY KEY (merchant_id, buyer_user_id)
);

CREATE INDEX IF NOT EXISTS idx_buyer_delivery_profile_merchant_buyer
    ON buyer_delivery_profile (merchant_id, buyer_user_id);
