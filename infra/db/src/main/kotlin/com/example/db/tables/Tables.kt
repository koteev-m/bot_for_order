package com.example.db.tables

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.javatime.timestamp

object ItemsTable : Table("items") {
    val id = varchar("id", 64)
    val title = text("title")
    val description = text("description")
    val status = varchar("status", 16)
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
}

object OrdersTable : Table("orders") {
    val id = varchar("id", 64)
    val userId = long("user_id")
    val itemId = reference("item_id", ItemsTable.id, onDelete = ReferenceOption.RESTRICT)
    val variantId = optReference("variant_id", VariantsTable.id, onDelete = ReferenceOption.SET_NULL)
    val qty = integer("qty")
    val currency = text("currency")
    val amountMinor = long("amount_minor")
    val deliveryOption = text("delivery_option").nullable()
    val addressJson = text("address_json").nullable()
    val provider = text("provider").nullable()
    val providerChargeId = text("provider_charge_id").nullable()
    val status = varchar("status", 16)
    val createdAt = timestamp("created_at")
    val updatedAt = timestamp("updated_at")
    override val primaryKey = PrimaryKey(id)
}

object OrderStatusHistoryTable : Table("order_status_history") {
    val id = long("id").autoIncrement()
    val orderId = reference("order_id", OrdersTable.id, onDelete = ReferenceOption.CASCADE)
    val status = varchar("status", 16)
    val comment = text("comment").nullable()
    val ts = timestamp("ts")
    val actorId = long("actor_id").nullable()
    override val primaryKey = PrimaryKey(id)
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
}
