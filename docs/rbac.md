# RBAC матрица (админка и операторка)

Роли:
- `OWNER` — полный доступ.
- `OPERATOR` — операционные действия по заказам и чтение настроек.
- `PAYMENTS` — ручные платежи + просмотр заказов/инбокса.
- `READONLY` — только просмотр (`list/get`), без мутаций.

## HTTP API `/api/admin`

| Действие | Endpoint | Разрешённые роли |
|---|---|---|
| Профиль администратора | `GET /me` | `READONLY`, `PAYMENTS`, `OPERATOR`, `OWNER` |
| Список заказов (inbox/bucket) | `GET /orders` | `READONLY`, `PAYMENTS`, `OPERATOR`, `OWNER` |
| Карточка заказа | `GET /orders/{id}` | `READONLY`, `PAYMENTS`, `OPERATOR`, `OWNER` |
| URL вложения по заказу | `GET /orders/{id}/attachments/{attachmentId}/url` | `READONLY`, `PAYMENTS`, `OPERATOR`, `OWNER` |
| Подтверждение оплаты | `POST /orders/{id}/payment/confirm` | `PAYMENTS`, `OPERATOR`, `OWNER` |
| Отклонение оплаты | `POST /orders/{id}/payment/reject` | `PAYMENTS`, `OPERATOR`, `OWNER` |
| Установка реквизитов оплаты | `POST /orders/{id}/payment/details` | `PAYMENTS`, `OPERATOR`, `OWNER` |
| Смена статуса заказа | `POST /orders/{id}/status` | `OPERATOR`, `OWNER` |
| Чтение настроек оплаты | `GET /settings/payment_methods` | `READONLY`, `PAYMENTS`, `OPERATOR`, `OWNER` |
| Изменение настроек оплаты | `PUT/POST /settings/payment_methods` | `OWNER` |
| Чтение настроек доставки | `GET /settings/delivery_method` | `READONLY`, `PAYMENTS`, `OPERATOR`, `OWNER` |
| Изменение настроек доставки | `PUT/POST /settings/delivery_method` | `OWNER` |
| Чтение витрин | `GET /settings/storefronts` | `READONLY`, `PAYMENTS`, `OPERATOR`, `OWNER` |
| Изменение витрин | `PUT/POST /settings/storefronts` | `OWNER` |
| Чтение привязок каналов | `GET /settings/channel_bindings` | `READONLY`, `PAYMENTS`, `OPERATOR`, `OWNER` |
| Изменение привязок каналов | `PUT/POST /settings/channel_bindings` | `OWNER` |
| Публикация | `POST /publications/publish` | `OWNER` |

## Telegram admin webhook (`/tg/admin`)

| Команда/действие | Разрешённые роли |
|---|---|
| Платёжные callback-действия (`confirm/reject/clarify/details`) | `PAYMENTS`, `OPERATOR`, `OWNER` |
| `/order` (просмотр заказа) | `READONLY`, `PAYMENTS`, `OPERATOR`, `OWNER` |
| `/start`, `/help` | все админ-роли |
| `/cancel` | `PAYMENTS`, `OPERATOR`, `OWNER` |
| Остальные mutating-команды (`/status`, `/post`, `/new`, `/media*`, `/counter`, `/stock`) | `OPERATOR`, `OWNER` |

## Примечания по безопасности

- При отсутствии доступа API отвечает `forbidden` без раскрытия матрицы ролей.
- Ошибки RBAC в Telegram возвращаются нейтральным сообщением `Команда недоступна.`.
