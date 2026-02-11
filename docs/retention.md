# Политики хранения данных и purge job

Документ описывает минимальный baseline по удержанию данных (retention) и автоматической очистке базы.

## Какие данные считаются PII

### `audit_log`
- `ip`
- `user_agent`

Эти поля используются только для расследования инцидентов и аудита действий. После окончания retention-периода поля анонимизируются (устанавливаются в `NULL`). Сама запись аудита не удаляется.

### Заказы и доставка
- `orders.address_json`
- `order_delivery.fields_json`

Это потенциально персональные данные получателя. Очистка применяется только к завершённым/давним заказам (`delivered`, `canceled`, `PAID_CONFIRMED`) и только если заказ старше cutoff по `orders.updated_at`.

## Технические таблицы и TTL

### `outbox_message`
Удаляются записи в статусах `DONE`/`FAILED`, которые старше TTL (`created_at < cutoff`).

### `telegram_webhook_dedup`
Удаляются:
- обработанные записи (`processed_at < cutoff`),
- зависшие in-progress записи (`processed_at IS NULL AND created_at < cutoff`).

### `idempotency_key`
Удаляются записи старше TTL (`created_at < cutoff`).

## Конфигурация

Все параметры задаются через env:

- `RETENTION_PURGE_ENABLED` — включение/выключение purge job.
- `RETENTION_PURGE_INTERVAL_HOURS` — период запуска.
- `RETENTION_AUDIT_LOG_DAYS` — retention для `audit_log.ip/user_agent`.
- `RETENTION_ORDER_DELIVERY_DAYS` — retention для `orders.address_json` и `order_delivery.fields_json`.
- `RETENTION_OUTBOX_DAYS` — TTL для очистки `outbox_message` (`DONE`/`FAILED`).
- `RETENTION_WEBHOOK_DEDUP_DAYS` — TTL для `telegram_webhook_dedup`.
- `RETENTION_IDEMPOTENCY_DAYS` — TTL для `idempotency_key`.

## Как работает purge job

- Запускается в фоне, вне request path.
- Не блокирует startup.
- Каждая операция идемпотентна: повторный запуск безопасен.
- Безопасна при параллельных запусках на нескольких инстансах: операции основаны на condition-based `UPDATE/DELETE`.
- Логируются только агрегаты (количество очищенных/удалённых записей), без вывода PII.
- Экспортируются метрики количества успешных и ошибочных прогонов (`retention_purge_runs_total`).
