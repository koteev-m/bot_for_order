-- Telegram payments columns for orders
ALTER TABLE orders
    ADD COLUMN IF NOT EXISTS telegram_payment_charge_id TEXT,
    ADD COLUMN IF NOT EXISTS invoice_message_id INT;
