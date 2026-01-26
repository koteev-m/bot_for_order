ALTER TABLE orders
    ALTER COLUMN status TYPE VARCHAR(32),
    ADD COLUMN IF NOT EXISTS payment_method_type VARCHAR(32),
    ADD COLUMN IF NOT EXISTS payment_method_selected_at TIMESTAMPTZ;

ALTER TABLE order_status_history
    ALTER COLUMN status TYPE VARCHAR(32);

CREATE TABLE IF NOT EXISTS merchant_payment_method (
    merchant_id VARCHAR(64) NOT NULL REFERENCES merchants(id) ON DELETE CASCADE,
    type VARCHAR(32) NOT NULL,
    mode VARCHAR(32) NOT NULL,
    details_encrypted TEXT,
    enabled BOOLEAN NOT NULL,
    PRIMARY KEY (merchant_id, type)
);

CREATE INDEX IF NOT EXISTS idx_merchant_payment_method_enabled
    ON merchant_payment_method(merchant_id, enabled);

CREATE TABLE IF NOT EXISTS order_payment_details (
    order_id VARCHAR(64) NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    provided_by_admin_id BIGINT NOT NULL,
    text TEXT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (order_id)
);

CREATE TABLE IF NOT EXISTS order_payment_claim (
    id BIGSERIAL PRIMARY KEY,
    order_id VARCHAR(64) NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    method_type VARCHAR(32) NOT NULL,
    txid TEXT,
    comment TEXT,
    created_at TIMESTAMPTZ NOT NULL,
    status VARCHAR(16) NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_order_payment_claim_order_id
    ON order_payment_claim(order_id);
CREATE INDEX IF NOT EXISTS idx_order_payment_claim_status
    ON order_payment_claim(status);

CREATE UNIQUE INDEX IF NOT EXISTS uq_order_payment_claim_submitted
    ON order_payment_claim(order_id)
    WHERE status = 'SUBMITTED';

CREATE TABLE IF NOT EXISTS order_attachment (
    id BIGSERIAL PRIMARY KEY,
    order_id VARCHAR(64) NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
    claim_id BIGINT REFERENCES order_payment_claim(id) ON DELETE SET NULL,
    kind VARCHAR(32) NOT NULL,
    storage_key TEXT,
    telegram_file_id TEXT,
    mime TEXT NOT NULL,
    size BIGINT NOT NULL,
    created_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_order_attachment_order_id
    ON order_attachment(order_id);
CREATE INDEX IF NOT EXISTS idx_order_attachment_claim_id
    ON order_attachment(claim_id);
