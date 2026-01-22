CREATE TABLE IF NOT EXISTS cart(
  id BIGSERIAL PRIMARY KEY,
  merchant_id VARCHAR(64) NOT NULL REFERENCES merchants(id) ON DELETE CASCADE,
  buyer_user_id BIGINT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  UNIQUE (merchant_id, buyer_user_id)
);

CREATE TABLE IF NOT EXISTS cart_item(
  id BIGSERIAL PRIMARY KEY,
  cart_id BIGINT NOT NULL REFERENCES cart(id) ON DELETE CASCADE,
  listing_id VARCHAR(64) NOT NULL REFERENCES items(id) ON DELETE RESTRICT,
  variant_id VARCHAR(64) REFERENCES variants(id) ON DELETE SET NULL,
  qty INT NOT NULL CHECK (qty > 0),
  price_snapshot_minor BIGINT NOT NULL,
  currency TEXT NOT NULL,
  source_storefront_id VARCHAR(64) NOT NULL,
  source_channel_id BIGINT NOT NULL,
  source_post_message_id INT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_cart_item_cart_id
    ON cart_item(cart_id);
CREATE INDEX IF NOT EXISTS idx_cart_item_listing_id
    ON cart_item(listing_id);
CREATE INDEX IF NOT EXISTS idx_cart_item_variant_id
    ON cart_item(variant_id);
