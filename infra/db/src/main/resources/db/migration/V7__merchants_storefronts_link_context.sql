CREATE TABLE IF NOT EXISTS merchants(
  id VARCHAR(64) PRIMARY KEY,
  name TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

INSERT INTO merchants (id, name)
VALUES ('default', 'Default Merchant')
ON CONFLICT (id) DO NOTHING;

CREATE TABLE IF NOT EXISTS storefronts(
  id VARCHAR(64) PRIMARY KEY,
  merchant_id VARCHAR(64) NOT NULL REFERENCES merchants(id) ON DELETE CASCADE,
  name TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS channel_bindings(
  id BIGSERIAL PRIMARY KEY,
  storefront_id VARCHAR(64) NOT NULL REFERENCES storefronts(id) ON DELETE CASCADE,
  channel_id BIGINT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_channel_bindings_channel_id
    ON channel_bindings(channel_id);
CREATE UNIQUE INDEX IF NOT EXISTS idx_channel_bindings_storefront_channel
    ON channel_bindings(storefront_id, channel_id);

CREATE TABLE IF NOT EXISTS link_contexts(
  id BIGSERIAL PRIMARY KEY,
  token_hash TEXT NOT NULL,
  merchant_id VARCHAR(64) NOT NULL REFERENCES merchants(id) ON DELETE CASCADE,
  storefront_id VARCHAR(64) NOT NULL REFERENCES storefronts(id) ON DELETE CASCADE,
  channel_id BIGINT NOT NULL,
  post_message_id INT,
  listing_id VARCHAR(64) NOT NULL REFERENCES items(id) ON DELETE RESTRICT,
  action TEXT NOT NULL,
  button TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  revoked_at TIMESTAMPTZ,
  expires_at TIMESTAMPTZ,
  metadata_json TEXT NOT NULL DEFAULT '{}'
);

CREATE UNIQUE INDEX IF NOT EXISTS idx_link_contexts_token_hash
    ON link_contexts(token_hash);
CREATE INDEX IF NOT EXISTS idx_link_contexts_listing_id
    ON link_contexts(listing_id);
CREATE INDEX IF NOT EXISTS idx_link_contexts_expires_at
    ON link_contexts(expires_at);

ALTER TABLE items
    ADD COLUMN IF NOT EXISTS merchant_id VARCHAR(64);
UPDATE items SET merchant_id = 'default' WHERE merchant_id IS NULL;
ALTER TABLE items
    ALTER COLUMN merchant_id SET DEFAULT 'default',
    ALTER COLUMN merchant_id SET NOT NULL;
ALTER TABLE items
    ADD CONSTRAINT fk_items_merchant FOREIGN KEY (merchant_id) REFERENCES merchants(id);

ALTER TABLE posts
    ADD COLUMN IF NOT EXISTS merchant_id VARCHAR(64);
UPDATE posts SET merchant_id = 'default' WHERE merchant_id IS NULL;
ALTER TABLE posts
    ALTER COLUMN merchant_id SET DEFAULT 'default',
    ALTER COLUMN merchant_id SET NOT NULL;
ALTER TABLE posts
    ADD CONSTRAINT fk_posts_merchant FOREIGN KEY (merchant_id) REFERENCES merchants(id);

ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS merchant_id VARCHAR(64);
UPDATE orders SET merchant_id = 'default' WHERE merchant_id IS NULL;
ALTER TABLE orders
    ALTER COLUMN merchant_id SET DEFAULT 'default',
    ALTER COLUMN merchant_id SET NOT NULL;
ALTER TABLE orders
    ADD CONSTRAINT fk_orders_merchant FOREIGN KEY (merchant_id) REFERENCES merchants(id);
