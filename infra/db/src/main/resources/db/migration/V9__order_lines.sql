ALTER TABLE merchants
    ADD COLUMN IF NOT EXISTS payment_claim_window_seconds INT NOT NULL DEFAULT 300,
    ADD COLUMN IF NOT EXISTS payment_review_window_seconds INT NOT NULL DEFAULT 900;

ALTER TABLE orders
    ALTER COLUMN item_id DROP NOT NULL,
    ALTER COLUMN qty DROP NOT NULL,
    ADD COLUMN IF NOT EXISTS payment_claimed_at TIMESTAMPTZ,
    ADD COLUMN IF NOT EXISTS payment_decided_at TIMESTAMPTZ;

CREATE TABLE IF NOT EXISTS order_line(
  order_id VARCHAR(64) NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
  listing_id VARCHAR(64) NOT NULL REFERENCES items(id) ON DELETE RESTRICT,
  variant_id VARCHAR(64) REFERENCES variants(id) ON DELETE SET NULL,
  qty INT NOT NULL CHECK (qty > 0),
  price_snapshot_minor BIGINT NOT NULL,
  currency TEXT NOT NULL,
  source_storefront_id VARCHAR(64),
  source_channel_id BIGINT,
  source_post_message_id INT
);

CREATE INDEX IF NOT EXISTS idx_order_line_order_id
    ON order_line(order_id);
CREATE INDEX IF NOT EXISTS idx_order_line_listing_id
    ON order_line(listing_id);
CREATE INDEX IF NOT EXISTS idx_order_line_variant_id
    ON order_line(variant_id);

INSERT INTO order_line(
    order_id,
    listing_id,
    variant_id,
    qty,
    price_snapshot_minor,
    currency,
    source_storefront_id,
    source_channel_id,
    source_post_message_id
)
SELECT
    o.id,
    o.item_id,
    o.variant_id,
    o.qty,
    o.amount_minor,
    o.currency,
    NULL,
    NULL,
    NULL
FROM orders o
WHERE o.item_id IS NOT NULL
  AND NOT EXISTS (
      SELECT 1 FROM order_line ol WHERE ol.order_id = o.id
  );
