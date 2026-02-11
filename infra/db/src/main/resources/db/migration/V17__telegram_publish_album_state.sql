CREATE TABLE IF NOT EXISTS telegram_publish_album_state (
    operation_id VARCHAR(64) PRIMARY KEY,
    item_id VARCHAR(64) NOT NULL,
    channel_id BIGINT NOT NULL,
    message_ids_json TEXT NULL,
    first_message_id INT NULL,
    add_token TEXT NULL,
    buy_token TEXT NULL,
    post_inserted BOOLEAN NOT NULL DEFAULT FALSE,
    edit_enqueued BOOLEAN NOT NULL DEFAULT FALSE,
    pin_enqueued BOOLEAN NOT NULL DEFAULT FALSE,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_telegram_publish_album_state_item_id
    ON telegram_publish_album_state (item_id);

CREATE INDEX IF NOT EXISTS idx_telegram_publish_album_state_channel_id
    ON telegram_publish_album_state (channel_id);
