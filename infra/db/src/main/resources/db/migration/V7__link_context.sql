CREATE TABLE IF NOT EXISTS link_context(
  id BIGSERIAL PRIMARY KEY,
  token VARCHAR(64) NOT NULL,
  merchant_id VARCHAR(64),
  storefront_id VARCHAR(64),
  channel_id BIGINT,
  post_id BIGINT,
  button VARCHAR(16),
  action VARCHAR(32) NOT NULL,
  item_id VARCHAR(64) REFERENCES items(id) ON DELETE SET NULL,
  variant_hint VARCHAR(64),
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  expires_at TIMESTAMPTZ,
  revoked_at TIMESTAMPTZ,
  meta_json TEXT
);

CREATE UNIQUE INDEX IF NOT EXISTS link_context_token_uq ON link_context(token);
CREATE INDEX IF NOT EXISTS link_context_channel_idx ON link_context(channel_id);
CREATE INDEX IF NOT EXISTS link_context_post_idx ON link_context(post_id);
