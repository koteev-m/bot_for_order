CREATE TABLE IF NOT EXISTS admin_user(
  merchant_id VARCHAR(64) NOT NULL REFERENCES merchants(id) ON DELETE CASCADE,
  user_id BIGINT NOT NULL,
  role VARCHAR(16) NOT NULL,
  created_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT NOW(),
  PRIMARY KEY (merchant_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_admin_user_merchant ON admin_user(merchant_id);
CREATE INDEX IF NOT EXISTS idx_admin_user_role ON admin_user(role);
