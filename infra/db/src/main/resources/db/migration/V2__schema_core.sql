-- Уточняем items (добавляем недостающие поля)
ALTER TABLE items
    ADD COLUMN IF NOT EXISTS status TEXT NOT NULL DEFAULT 'draft',
    ADD COLUMN IF NOT EXISTS allow_bargain BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN IF NOT EXISTS bargain_rules_json TEXT,
    ADD COLUMN IF NOT EXISTS created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    ADD COLUMN IF NOT EXISTS updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW();

CREATE TABLE IF NOT EXISTS item_media(
  id BIGSERIAL PRIMARY KEY,
  item_id VARCHAR(64) NOT NULL REFERENCES items(id) ON DELETE CASCADE,
  file_id TEXT NOT NULL,
  media_type VARCHAR(16) NOT NULL,
  sort_order INT NOT NULL DEFAULT 0
);

CREATE TABLE IF NOT EXISTS variants(
  id VARCHAR(64) PRIMARY KEY,
  item_id VARCHAR(64) NOT NULL REFERENCES items(id) ON DELETE CASCADE,
  size TEXT,
  sku TEXT,
  stock INT NOT NULL DEFAULT 0,
  active BOOLEAN NOT NULL DEFAULT TRUE
);

CREATE TABLE IF NOT EXISTS prices_display(
  item_id VARCHAR(64) PRIMARY KEY REFERENCES items(id) ON DELETE CASCADE,
  base_currency CHAR(3) NOT NULL,
  base_amount_minor BIGINT NOT NULL,
  display_rub BIGINT,
  display_usd BIGINT,
  display_eur BIGINT,
  display_usdt_ts BIGINT,
  fx_source TEXT,
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS posts(
  id BIGSERIAL PRIMARY KEY,
  item_id VARCHAR(64) NOT NULL REFERENCES items(id) ON DELETE CASCADE,
  channel_msg_ids_json TEXT NOT NULL,
  posted_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS offers(
  id VARCHAR(64) PRIMARY KEY,
  item_id VARCHAR(64) NOT NULL REFERENCES items(id) ON DELETE CASCADE,
  variant_id VARCHAR(64) REFERENCES variants(id) ON DELETE SET NULL,
  user_id BIGINT NOT NULL,
  offer_amount_minor BIGINT NOT NULL,
  status TEXT NOT NULL,
  counters_used INT NOT NULL DEFAULT 0,
  expires_at TIMESTAMPTZ,
  last_counter_amount BIGINT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_offers_item ON offers(item_id);
CREATE INDEX IF NOT EXISTS idx_offers_user ON offers(user_id);

CREATE TABLE IF NOT EXISTS orders(
  id VARCHAR(64) PRIMARY KEY,
  user_id BIGINT NOT NULL,
  item_id VARCHAR(64) NOT NULL REFERENCES items(id) ON DELETE RESTRICT,
  variant_id VARCHAR(64) REFERENCES variants(id) ON DELETE SET NULL,
  qty INT NOT NULL,
  currency TEXT NOT NULL,
  amount_minor BIGINT NOT NULL,
  delivery_option TEXT,
  address_json TEXT,
  provider TEXT,
  provider_charge_id TEXT,
  status TEXT NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_orders_user ON orders(user_id);

CREATE TABLE IF NOT EXISTS order_status_history(
  id BIGSERIAL PRIMARY KEY,
  order_id VARCHAR(64) NOT NULL REFERENCES orders(id) ON DELETE CASCADE,
  status TEXT NOT NULL,
  comment TEXT,
  ts TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  actor_id BIGINT
);

CREATE INDEX IF NOT EXISTS idx_osh_order ON order_status_history(order_id);

CREATE TABLE IF NOT EXISTS watchlist(
  id BIGSERIAL PRIMARY KEY,
  user_id BIGINT NOT NULL,
  item_id VARCHAR(64) NOT NULL REFERENCES items(id) ON DELETE CASCADE,
  variant_id VARCHAR(64) REFERENCES variants(id) ON DELETE SET NULL,
  trigger_type TEXT NOT NULL,
  params TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_watch_user ON watchlist(user_id);
CREATE INDEX IF NOT EXISTS idx_watch_item ON watchlist(item_id);
