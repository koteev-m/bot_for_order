package com.example.db.tables

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object MerchantsTable : Table("merchants") {
    val id = varchar("id", 64)
    val name = text("name")
    val paymentClaimWindowSeconds = integer("payment_claim_window_seconds")
    val paymentReviewWindowSeconds = integer("payment_review_window_seconds")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object AdminUsersTable : Table("admin_user") {
    val merchantId = reference("merchant_id", MerchantsTable.id, onDelete = ReferenceOption.CASCADE)
    val userId = long("user_id")
    val role = varchar("role", 16)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(merchantId, userId)

    init {
        index(false, merchantId)
        index(false, role)
    }
}

object StorefrontsTable : Table("storefronts") {
    val id = varchar("id", 64)
    val merchantId = reference("merchant_id", MerchantsTable.id, onDelete = ReferenceOption.CASCADE)
    val name = text("name")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)
}

object ChannelBindingsTable : Table("channel_bindings") {
    val id = long("id").autoIncrement()
    val storefrontId = reference("storefront_id", StorefrontsTable.id, onDelete = ReferenceOption.CASCADE)
    val channelId = long("channel_id")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)

    init {
        index(true, channelId)
        index(true, storefrontId, channelId)
    }
}

object LinkContextsTable : Table("link_contexts") {
    val id = long("id").autoIncrement()
    val tokenHash = text("token_hash")
    val merchantId = reference("merchant_id", MerchantsTable.id, onDelete = ReferenceOption.CASCADE)
    val storefrontId = reference("storefront_id", StorefrontsTable.id, onDelete = ReferenceOption.CASCADE)
    val channelId = long("channel_id")
    val postMessageId = integer("post_message_id").nullable()
    val listingId = reference("listing_id", ItemsTable.id, onDelete = ReferenceOption.RESTRICT)
    val action = varchar("action", 8)
    val button = varchar("button", 8)
    val createdAt = timestamp("created_at")
    val revokedAt = timestamp("revoked_at").nullable()
    val expiresAt = timestamp("expires_at").nullable()
    val metadataJson = text("metadata_json")
    override val primaryKey = PrimaryKey(id)

    init {
        index(true, tokenHash)
        index(false, listingId)
        index(false, expiresAt)
    }
}

object ItemsTable : Table("items") {
    val id = varchar("id", 64)
    val merchantId = reference("merchant_id", MerchantsTable.id, onDelete = ReferenceOption.RESTRICT)
    val title = text("title")
    val description = text("description")
    val status = varchar("status", 32)
    val allowBargain = bool("allow_bargain")
    val bargainRulesJson = text("bargain_rules_json").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object ItemMediaTable : Table("item_media") {
    val id = long("id").autoIncrement()
    val itemId = reference("item_id", ItemsTable.id, onDelete = ReferenceOption.CASCADE)
    val fileId = text("file_id")
    val mediaType = varchar("media_type", 16)
    val sortOrder = integer("sort_order")
    override val primaryKey = PrimaryKey(id)
}

object VariantsTable : Table("variants") {
    val id = varchar("id", 64)
    val itemId = reference("item_id", ItemsTable.id, onDelete = ReferenceOption.CASCADE)
    val size = text("size").nullable()
    val sku = text("sku").nullable()
    val stock = integer("stock")
    val active = bool("active")
    override val primaryKey = PrimaryKey(id)
}

object PricesDisplayTable : Table("prices_display") {
    val itemId = reference("item_id", ItemsTable.id, onDelete = ReferenceOption.CASCADE)
    val baseCurrency = char("base_currency", 3)
    val baseAmountMinor = long("base_amount_minor")
    val invoiceAmountMinor = long("invoice_amount_minor").nullable()
    val displayRub = long("display_rub").nullable()
    val displayUsd = long("display_usd").nullable()
    val displayEur = long("display_eur").nullable()
    val displayUsdtTs = long("display_usdt_ts").nullable()
    val fxSource = text("fx_source").nullable()
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(itemId)
}

object PostsTable : Table("posts") {
    val id = long("id").autoIncrement()
    val merchantId = reference("merchant_id", MerchantsTable.id, onDelete = ReferenceOption.RESTRICT)
    val itemId = reference("item_id", ItemsTable.id, onDelete = ReferenceOption.CASCADE)
    val channelMsgIdsJson = text("channel_msg_ids_json")
    val postedAt = timestamp("posted_at")
    override val primaryKey = PrimaryKey(id)
}

object OffersTable : Table("offers") {
    val id = varchar("id", 64)
    val itemId = reference("item_id", ItemsTable.id, onDelete = ReferenceOption.CASCADE)
    val variantId = optReference("variant_id", VariantsTable.id, onDelete = ReferenceOption.SET_NULL)
    val userId = long("user_id")
    val offerAmountMinor = long("offer_amount_minor")
    val status = varchar("status", 16)
    val countersUsed = integer("counters_used")
    val expiresAt = timestamp("expires_at").nullable()
    val lastCounterAmount = long("last_counter_amount").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)

    init {
        index(false, expiresAt)
        index(false, itemId, userId, status)
    }
}

object OrdersTable : Table("orders") {
    val id = varchar("id", 64)
    val merchantId = reference("merchant_id", MerchantsTable.id, onDelete = ReferenceOption.RESTRICT)
    val userId = long("user_id")
    val itemId = optReference("item_id", ItemsTable.id, onDelete = ReferenceOption.RESTRICT)
    val variantId = optReference("variant_id", VariantsTable.id, onDelete = ReferenceOption.SET_NULL)
    val qty = integer("qty").nullable()
    val currency = text("currency")
    val amountMinor = long("amount_minor")
    val deliveryOption = text("delivery_option").nullable()
    val addressJson = text("address_json").nullable()
    val provider = text("provider").nullable()
    val providerChargeId = text("provider_charge_id").nullable()
    val telegramPaymentChargeId = text("telegram_payment_charge_id").nullable()
    val invoiceMessageId = integer("invoice_message_id").nullable()
    val status = varchar("status", 32)
    val paymentClaimedAt = timestamp("payment_claimed_at").nullable()
    val paymentDecidedAt = timestamp("payment_decided_at").nullable()
    val paymentMethodType = varchar("payment_method_type", 32).nullable()
    val paymentMethodSelectedAt = timestamp("payment_method_selected_at").nullable()
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object MerchantPaymentMethodsTable : Table("merchant_payment_method") {
    val merchantId = reference("merchant_id", MerchantsTable.id, onDelete = ReferenceOption.CASCADE)
    val type = varchar("type", 32)
    val mode = varchar("mode", 32)
    val detailsEncrypted = text("details_encrypted").nullable()
    val enabled = bool("enabled")

    init {
        index(false, merchantId, enabled)
    }

    override val primaryKey = PrimaryKey(merchantId, type)
}

object MerchantDeliveryMethodsTable : Table("merchant_delivery_method") {
    val merchantId = reference("merchant_id", MerchantsTable.id, onDelete = ReferenceOption.CASCADE)
    val type = varchar("type", 32)
    val enabled = bool("enabled")
    val requiredFieldsJson = text("required_fields_json")

    init {
        index(false, merchantId, enabled)
    }

    override val primaryKey = PrimaryKey(merchantId, type)
}

object OrderPaymentDetailsTable : Table("order_payment_details") {
    val orderId = reference("order_id", OrdersTable.id, onDelete = ReferenceOption.CASCADE)
    val providedByAdminId = long("provided_by_admin_id")
    val text = text("text")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(orderId)
}

object OrderPaymentClaimsTable : Table("order_payment_claim") {
    val id = long("id").autoIncrement()
    val orderId = reference("order_id", OrdersTable.id, onDelete = ReferenceOption.CASCADE)
    val methodType = varchar("method_type", 32)
    val txid = text("txid").nullable()
    val comment = text("comment").nullable()
    val createdAt = timestamp("created_at")
    val status = varchar("status", 16)
    override val primaryKey = PrimaryKey(id)

    init {
        index(false, orderId)
        index(false, status)
    }
}

object OrderAttachmentsTable : Table("order_attachment") {
    val id = long("id").autoIncrement()
    val orderId = reference("order_id", OrdersTable.id, onDelete = ReferenceOption.CASCADE)
    val claimId = optReference("claim_id", OrderPaymentClaimsTable.id, onDelete = ReferenceOption.SET_NULL)
    val kind = varchar("kind", 32)
    val storageKey = text("storage_key").nullable()
    val telegramFileId = text("telegram_file_id").nullable()
    val mime = text("mime")
    val size = long("size")
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)

    init {
        index(false, orderId)
        index(false, claimId)
    }
}

object OrderDeliveryTable : Table("order_delivery") {
    val orderId = reference("order_id", OrdersTable.id, onDelete = ReferenceOption.CASCADE)
    val type = varchar("type", 32)
    val fieldsJson = text("fields_json")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(orderId)

    init {
        index(false, orderId)
    }
}

object OrderLinesTable : Table("order_line") {
    val orderId = reference("order_id", OrdersTable.id, onDelete = ReferenceOption.CASCADE)
    val listingId = reference("listing_id", ItemsTable.id, onDelete = ReferenceOption.RESTRICT)
    val variantId = optReference("variant_id", VariantsTable.id, onDelete = ReferenceOption.SET_NULL)
    val qty = integer("qty")
    val priceSnapshotMinor = long("price_snapshot_minor")
    val currency = text("currency")
    val sourceStorefrontId = varchar("source_storefront_id", 64).nullable()
    val sourceChannelId = long("source_channel_id").nullable()
    val sourcePostMessageId = integer("source_post_message_id").nullable()

    init {
        index(false, orderId)
        index(false, listingId)
        index(false, variantId)
    }
}

object CartsTable : Table("cart") {
    val id = long("id").autoIncrement()
    val merchantId = reference("merchant_id", MerchantsTable.id, onDelete = ReferenceOption.CASCADE)
    val buyerUserId = long("buyer_user_id")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)

    init {
        index(true, merchantId, buyerUserId)
    }
}

object BuyerDeliveryProfileTable : Table("buyer_delivery_profile") {
    val merchantId = reference("merchant_id", MerchantsTable.id, onDelete = ReferenceOption.CASCADE)
    val buyerUserId = long("buyer_user_id")
    val fieldsJson = text("fields_json")
    val updatedAt = timestamp("updated_at")

    init {
        index(false, merchantId, buyerUserId)
    }

    override val primaryKey = PrimaryKey(merchantId, buyerUserId)
}

object CartItemsTable : Table("cart_item") {
    val id = long("id").autoIncrement()
    val cartId = reference("cart_id", CartsTable.id, onDelete = ReferenceOption.CASCADE)
    val listingId = reference("listing_id", ItemsTable.id, onDelete = ReferenceOption.RESTRICT)
    val variantId = optReference("variant_id", VariantsTable.id, onDelete = ReferenceOption.SET_NULL)
    val qty = integer("qty")
    val priceSnapshotMinor = long("price_snapshot_minor")
    val currency = text("currency")
    val sourceStorefrontId = varchar("source_storefront_id", 64)
    val sourceChannelId = long("source_channel_id")
    val sourcePostMessageId = integer("source_post_message_id").nullable()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)

    init {
        index(false, cartId)
        index(false, listingId)
        index(false, variantId)
    }
}

object OrderStatusHistoryTable : Table("order_status_history") {
    val id = long("id").autoIncrement()
    val orderId = reference("order_id", OrdersTable.id, onDelete = ReferenceOption.CASCADE)
    val status = varchar("status", 32)
    val comment = text("comment").nullable()
    val ts = timestamp("ts")
    val actorId = long("actor_id").nullable()
    override val primaryKey = PrimaryKey(id)
}

object AuditLogTable : Table("audit_log") {
    val id = long("id").autoIncrement()
    val adminUserId = long("admin_user_id")
    val action = text("action")
    val orderId = varchar("order_id", 64).nullable()
    val payloadJson = text("payload_json")
    val createdAt = timestamp("created_at")
    val ip = text("ip").nullable()
    val userAgent = text("user_agent").nullable()
    override val primaryKey = PrimaryKey(id)

    init {
        index(false, adminUserId, createdAt)
        index(false, orderId)
    }
}

object EventLogTable : Table("event_log") {
    val id = long("id").autoIncrement()
    val ts = timestamp("ts")
    val eventType = text("event_type")
    val buyerUserId = long("buyer_user_id").nullable()
    val merchantId = reference("merchant_id", MerchantsTable.id, onDelete = ReferenceOption.CASCADE)
    val storefrontId = varchar("storefront_id", 64).nullable()
    val channelId = long("channel_id").nullable()
    val postMessageId = integer("post_message_id").nullable()
    val listingId = varchar("listing_id", 64).nullable()
    val variantId = varchar("variant_id", 64).nullable()
    val metadataJson = text("metadata_json").nullable()
    override val primaryKey = PrimaryKey(id)

    init {
        index(false, merchantId, ts)
        index(false, eventType, ts)
        index(false, buyerUserId, ts)
    }
}

object IdempotencyKeyTable : Table("idempotency_key") {
    val merchantId = reference("merchant_id", MerchantsTable.id, onDelete = ReferenceOption.CASCADE)
    val userId = long("user_id")
    val scope = varchar("scope", 64)
    val key = varchar("key", 128)
    val requestHash = char("request_hash", 64)
    val responseStatus = integer("response_status").nullable()
    val responseJson = text("response_json").nullable()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(merchantId, userId, scope, key)

    init {
        index(false, merchantId, userId, scope)
    }
}


object OutboxMessageTable : Table("outbox_message") {
    val id = long("id").autoIncrement()
    val type = text("type")
    val payloadJson = text("payload_json")
    val status = varchar("status", 16)
    val attempts = integer("attempts")
    val nextAttemptAt = timestamp("next_attempt_at")
    val createdAt = timestamp("created_at")
    val lastError = text("last_error").nullable()

    override val primaryKey = PrimaryKey(id)

    init {
        index(false, status, nextAttemptAt)
        index(false, createdAt)
    }
}

object TelegramPublishAlbumStateTable : Table("telegram_publish_album_state") {
    val operationId = varchar("operation_id", 64)
    val itemId = varchar("item_id", 64)
    val channelId = long("channel_id")
    val messageIdsJson = text("message_ids_json").nullable()
    val firstMessageId = integer("first_message_id").nullable()
    val addToken = text("add_token").nullable()
    val buyToken = text("buy_token").nullable()
    val postInserted = bool("post_inserted")
    val editEnqueued = bool("edit_enqueued")
    val pinEnqueued = bool("pin_enqueued")
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")

    override val primaryKey = PrimaryKey(operationId)

    init {
        index(false, itemId)
        index(false, channelId)
    }
}

object TelegramWebhookDedupTable : Table("telegram_webhook_dedup") {
    val botType = varchar("bot_type", 32)
    val updateId = long("update_id")
    val createdAt = timestamp("created_at")
    val processedAt = timestamp("processed_at").nullable()

    override val primaryKey = PrimaryKey(botType, updateId)

    init {
        index(false, createdAt)
        index(false, processedAt, createdAt)
    }
}

object WatchlistTable : Table("watchlist") {
    val id = long("id").autoIncrement()
    val userId = long("user_id")
    val itemId = reference("item_id", ItemsTable.id, onDelete = ReferenceOption.CASCADE)
    val variantId = optReference("variant_id", VariantsTable.id, onDelete = ReferenceOption.SET_NULL)
    val triggerType = varchar("trigger_type", 16)
    val params = text("params").nullable()
    val createdAt = timestamp("created_at")
    override val primaryKey = PrimaryKey(id)

    init {
        index(false, triggerType, itemId, variantId)
        index(false, userId, triggerType)
    }
}
