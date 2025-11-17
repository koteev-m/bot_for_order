package com.example.db

import com.example.db.tables.ItemMediaTable
import com.example.db.tables.ItemsTable
import com.example.db.tables.OffersTable
import com.example.db.tables.OrderStatusHistoryTable
import com.example.db.tables.OrdersTable
import com.example.db.tables.PostsTable
import com.example.db.tables.PricesDisplayTable
import com.example.db.tables.VariantsTable
import com.example.db.tables.WatchlistTable
import com.example.domain.BargainRules
import com.example.domain.Item
import com.example.domain.ItemMedia
import com.example.domain.ItemStatus
import com.example.domain.Offer
import com.example.domain.OfferStatus
import com.example.domain.Order
import com.example.domain.OrderStatus
import com.example.domain.OrderStatusEntry
import com.example.domain.Post
import com.example.domain.PricesDisplay
import com.example.domain.Variant
import com.example.domain.WatchEntry
import com.example.domain.WatchTrigger
import java.time.Instant
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.statements.InsertStatement

private val json = Json

class ItemsRepositoryExposed(private val tx: DatabaseTx) : ItemsRepository {
    override suspend fun create(item: Item) {
        tx.tx {
            ItemsTable.insert {
                it[id] = item.id
                it[title] = item.title
                it[description] = item.description
                it[status] = item.status.name
                it[allowBargain] = item.allowBargain
                it[bargainRulesJson] = item.bargainRules?.let(json::encodeToString)
                it[createdAt] = CurrentTimestamp()
                it[updatedAt] = CurrentTimestamp()
            }
        }
    }

    override suspend fun getById(id: String): Item? = tx.tx {
        ItemsTable
            .selectAll()
            .where { ItemsTable.id eq id }
            .singleOrNull()
            ?.toItem()
    }

    override suspend fun setStatus(id: String, status: ItemStatus, allowBargain: Boolean, bargainRules: BargainRules?) {
        tx.tx {
            ItemsTable.update({ ItemsTable.id eq id }) {
                it[ItemsTable.status] = status.name
                it[ItemsTable.allowBargain] = allowBargain
                it[ItemsTable.bargainRulesJson] = bargainRules?.let(json::encodeToString)
                it[updatedAt] = CurrentTimestamp()
            }
        }
    }

    override suspend fun listActive(): List<Item> = tx.tx {
        ItemsTable
            .selectAll()
            .where { ItemsTable.status eq ItemStatus.active.name }
            .map { it.toItem() }
    }

    private fun ResultRow.toItem(): Item =
        Item(
            id = this[ItemsTable.id],
            title = this[ItemsTable.title],
            description = this[ItemsTable.description],
            status = ItemStatus.valueOf(this[ItemsTable.status]),
            allowBargain = this[ItemsTable.allowBargain],
            bargainRules = this[ItemsTable.bargainRulesJson]?.let { json.decodeFromString<BargainRules>(it) }
        )
}

class ItemMediaRepositoryExposed(private val tx: DatabaseTx) : ItemMediaRepository {
    override suspend fun add(media: ItemMedia): Long = tx.tx {
        ItemMediaTable.insert {
            it[itemId] = media.itemId
            it[fileId] = media.fileId
            it[mediaType] = media.mediaType
            it[sortOrder] = media.sortOrder
        }.requireGeneratedId(ItemMediaTable.id)
    }

    override suspend fun listByItem(itemId: String): List<ItemMedia> = tx.tx {
        ItemMediaTable
            .selectAll()
            .where { ItemMediaTable.itemId eq itemId }
            .orderBy(ItemMediaTable.sortOrder to SortOrder.ASC)
            .map {
                ItemMedia(
                    id = it[ItemMediaTable.id],
                    itemId = it[ItemMediaTable.itemId],
                    fileId = it[ItemMediaTable.fileId],
                    mediaType = it[ItemMediaTable.mediaType],
                    sortOrder = it[ItemMediaTable.sortOrder]
                )
            }
    }

    override suspend fun deleteByItem(itemId: String) {
        tx.tx {
            ItemMediaTable.deleteWhere { ItemMediaTable.itemId eq itemId }
        }
    }
}

class VariantsRepositoryExposed(private val tx: DatabaseTx) : VariantsRepository {
    override suspend fun upsert(variant: Variant) {
        tx.tx {
            val updated = VariantsTable.update({ VariantsTable.id eq variant.id }) {
                it[itemId] = variant.itemId
                it[size] = variant.size
                it[sku] = variant.sku
                it[stock] = variant.stock
                it[active] = variant.active
            }
            if (updated == 0) {
                VariantsTable.insert {
                    it[id] = variant.id
                    it[itemId] = variant.itemId
                    it[size] = variant.size
                    it[sku] = variant.sku
                    it[stock] = variant.stock
                    it[active] = variant.active
                }
            }
        }
    }

    override suspend fun listByItem(itemId: String): List<Variant> = tx.tx {
        VariantsTable
            .selectAll()
            .where { VariantsTable.itemId eq itemId }
            .map {
                Variant(
                    id = it[VariantsTable.id],
                    itemId = it[VariantsTable.itemId],
                    size = it[VariantsTable.size],
                    sku = it[VariantsTable.sku],
                    stock = it[VariantsTable.stock],
                    active = it[VariantsTable.active]
                )
            }
    }

    override suspend fun setStock(variantId: String, stock: Int) {
        tx.tx {
            VariantsTable.update({ VariantsTable.id eq variantId }) {
                it[VariantsTable.stock] = stock
            }
        }
    }
}

class PricesDisplayRepositoryExposed(private val tx: DatabaseTx) : PricesDisplayRepository {
    override suspend fun upsert(p: PricesDisplay) {
        tx.tx {
            val updated = PricesDisplayTable.update({ PricesDisplayTable.itemId eq p.itemId }) {
                it[baseCurrency] = p.baseCurrency
                it[baseAmountMinor] = p.baseAmountMinor
                it[displayRub] = p.displayRub
                it[displayUsd] = p.displayUsd
                it[displayEur] = p.displayEur
                it[displayUsdtTs] = p.displayUsdtTs
                it[fxSource] = p.fxSource
                it[updatedAt] = CurrentTimestamp()
            }
            if (updated == 0) {
                PricesDisplayTable.insert {
                    it[itemId] = p.itemId
                    it[baseCurrency] = p.baseCurrency
                    it[baseAmountMinor] = p.baseAmountMinor
                    it[displayRub] = p.displayRub
                    it[displayUsd] = p.displayUsd
                    it[displayEur] = p.displayEur
                    it[displayUsdtTs] = p.displayUsdtTs
                    it[fxSource] = p.fxSource
                    it[updatedAt] = CurrentTimestamp()
                }
            }
        }
    }

    override suspend fun get(itemId: String): PricesDisplay? = tx.tx {
        PricesDisplayTable
            .selectAll()
            .where { PricesDisplayTable.itemId eq itemId }
            .singleOrNull()
            ?.let {
                PricesDisplay(
                    itemId = it[PricesDisplayTable.itemId],
                    baseCurrency = it[PricesDisplayTable.baseCurrency],
                    baseAmountMinor = it[PricesDisplayTable.baseAmountMinor],
                    displayRub = it[PricesDisplayTable.displayRub],
                    displayUsd = it[PricesDisplayTable.displayUsd],
                    displayEur = it[PricesDisplayTable.displayEur],
                    displayUsdtTs = it[PricesDisplayTable.displayUsdtTs],
                    fxSource = it[PricesDisplayTable.fxSource]
                )
            }
    }
}

class PostsRepositoryExposed(private val tx: DatabaseTx) : PostsRepository {
    override suspend fun insert(post: Post): Long = tx.tx {
        val payload = json.encodeToString(post.channelMsgIds)
        PostsTable.insert {
            it[itemId] = post.itemId
            it[channelMsgIdsJson] = payload
            it[postedAt] = CurrentTimestamp()
        }.requireGeneratedId(PostsTable.id)
    }

    override suspend fun listByItem(itemId: String): List<Post> = tx.tx {
        PostsTable
            .selectAll()
            .where { PostsTable.itemId eq itemId }
            .orderBy(PostsTable.postedAt to SortOrder.DESC)
            .map {
                Post(
                    id = it[PostsTable.id],
                    itemId = it[PostsTable.itemId],
                    channelMsgIds = json.decodeFromString<List<Int>>(it[PostsTable.channelMsgIdsJson])
                )
            }
    }
}

class OffersRepositoryExposed(private val tx: DatabaseTx) : OffersRepository {
    override suspend fun create(offer: Offer) {
        tx.tx {
            OffersTable.insert {
                it[id] = offer.id
                it[itemId] = offer.itemId
                it[variantId] = offer.variantId
                it[userId] = offer.userId
                it[offerAmountMinor] = offer.offerAmountMinor
                it[status] = offer.status.name
                it[countersUsed] = offer.countersUsed
                it[expiresAt] = offer.expiresAtIso?.let(Instant::parse)
                it[lastCounterAmount] = offer.lastCounterAmount
                it[createdAt] = CurrentTimestamp()
                it[updatedAt] = CurrentTimestamp()
            }
        }
    }

    override suspend fun get(id: String): Offer? = tx.tx {
        OffersTable
            .selectAll()
            .where { OffersTable.id eq id }
            .singleOrNull()
            ?.let {
                Offer(
                    id = it[OffersTable.id],
                    itemId = it[OffersTable.itemId],
                    variantId = it[OffersTable.variantId],
                    userId = it[OffersTable.userId],
                    offerAmountMinor = it[OffersTable.offerAmountMinor],
                    status = OfferStatus.valueOf(it[OffersTable.status]),
                    countersUsed = it[OffersTable.countersUsed],
                    expiresAtIso = it[OffersTable.expiresAt]?.toString(),
                    lastCounterAmount = it[OffersTable.lastCounterAmount]
                )
            }
    }

    override suspend fun setStatus(id: String, status: OfferStatus, countersUsed: Int, lastCounterAmount: Long?) {
        tx.tx {
            OffersTable.update({ OffersTable.id eq id }) {
                it[OffersTable.status] = status.name
                it[OffersTable.countersUsed] = countersUsed
                it[OffersTable.lastCounterAmount] = lastCounterAmount
                it[OffersTable.updatedAt] = CurrentTimestamp()
            }
        }
    }
}

class OrdersRepositoryExposed(private val tx: DatabaseTx) : OrdersRepository {
    override suspend fun create(order: Order) {
        tx.tx {
            OrdersTable.insert {
                it[id] = order.id
                it[userId] = order.userId
                it[itemId] = order.itemId
                it[variantId] = order.variantId
                it[qty] = order.qty
                it[currency] = order.currency
                it[amountMinor] = order.amountMinor
                it[deliveryOption] = order.deliveryOption
                it[addressJson] = order.addressJson
                it[provider] = order.provider
                it[providerChargeId] = order.providerChargeId
                it[telegramPaymentChargeId] = order.telegramPaymentChargeId
                it[invoiceMessageId] = order.invoiceMessageId
                it[status] = order.status.name
                it[createdAt] = CurrentTimestamp()
                it[updatedAt] = CurrentTimestamp()
            }
        }
    }

    override suspend fun get(id: String): Order? = tx.tx {
        OrdersTable
            .selectAll()
            .where { OrdersTable.id eq id }
            .singleOrNull()
            ?.let {
                Order(
                    id = it[OrdersTable.id],
                    userId = it[OrdersTable.userId],
                    itemId = it[OrdersTable.itemId],
                    variantId = it[OrdersTable.variantId],
                    qty = it[OrdersTable.qty],
                    currency = it[OrdersTable.currency],
                    amountMinor = it[OrdersTable.amountMinor],
                    deliveryOption = it[OrdersTable.deliveryOption],
                    addressJson = it[OrdersTable.addressJson],
                    provider = it[OrdersTable.provider],
                    providerChargeId = it[OrdersTable.providerChargeId],
                    telegramPaymentChargeId = it[OrdersTable.telegramPaymentChargeId],
                    invoiceMessageId = it[OrdersTable.invoiceMessageId],
                    status = OrderStatus.valueOf(it[OrdersTable.status]),
                    updatedAt = it[OrdersTable.updatedAt]
                )
            }
    }

    override suspend fun listByUser(userId: Long): List<Order> = tx.tx {
        OrdersTable
            .selectAll()
            .where { OrdersTable.userId eq userId }
            .orderBy(OrdersTable.updatedAt to SortOrder.DESC)
            .map {
                Order(
                    id = it[OrdersTable.id],
                    userId = it[OrdersTable.userId],
                    itemId = it[OrdersTable.itemId],
                    variantId = it[OrdersTable.variantId],
                    qty = it[OrdersTable.qty],
                    currency = it[OrdersTable.currency],
                    amountMinor = it[OrdersTable.amountMinor],
                    deliveryOption = it[OrdersTable.deliveryOption],
                    addressJson = it[OrdersTable.addressJson],
                    provider = it[OrdersTable.provider],
                    providerChargeId = it[OrdersTable.providerChargeId],
                    telegramPaymentChargeId = it[OrdersTable.telegramPaymentChargeId],
                    invoiceMessageId = it[OrdersTable.invoiceMessageId],
                    status = OrderStatus.valueOf(it[OrdersTable.status]),
                    updatedAt = it[OrdersTable.updatedAt]
                )
            }
    }

    override suspend fun setStatus(id: String, status: OrderStatus) {
        tx.tx {
            OrdersTable.update({ OrdersTable.id eq id }) {
                it[OrdersTable.status] = status.name
                it[OrdersTable.updatedAt] = CurrentTimestamp()
            }
        }
    }

    override suspend fun setInvoiceMessage(id: String, invoiceMessageId: Int) {
        tx.tx {
            OrdersTable.update({ OrdersTable.id eq id }) {
                it[OrdersTable.invoiceMessageId] = invoiceMessageId
                it[OrdersTable.updatedAt] = CurrentTimestamp()
            }
        }
    }

    override suspend fun markPaid(
        id: String,
        provider: String,
        providerChargeId: String,
        telegramPaymentChargeId: String
    ) {
        tx.tx {
            OrdersTable.update({ OrdersTable.id eq id }) {
                it[OrdersTable.status] = OrderStatus.paid.name
                it[OrdersTable.provider] = provider
                it[OrdersTable.providerChargeId] = providerChargeId
                it[OrdersTable.telegramPaymentChargeId] = telegramPaymentChargeId
                it[OrdersTable.updatedAt] = CurrentTimestamp()
            }
        }
    }
}

class OrderStatusHistoryRepositoryExposed(private val tx: DatabaseTx) : OrderStatusHistoryRepository {
    override suspend fun append(entry: OrderStatusEntry): Long = tx.tx {
        OrderStatusHistoryTable.insert {
            it[orderId] = entry.orderId
            it[status] = entry.status.name
            it[comment] = entry.comment
            it[ts] = entry.ts
            it[actorId] = entry.actorId
        }.requireGeneratedId(OrderStatusHistoryTable.id)
    }

    override suspend fun list(orderId: String, limit: Int?): List<OrderStatusEntry> = tx.tx {
        val sortOrder = if (limit != null) SortOrder.DESC else SortOrder.ASC
        val query = OrderStatusHistoryTable
            .selectAll()
            .where { OrderStatusHistoryTable.orderId eq orderId }
            .orderBy(OrderStatusHistoryTable.ts to sortOrder)
        if (limit != null) {
            query.limit(limit)
        }
        query.map {
            OrderStatusEntry(
                id = it[OrderStatusHistoryTable.id],
                orderId = it[OrderStatusHistoryTable.orderId],
                status = OrderStatus.valueOf(it[OrderStatusHistoryTable.status]),
                comment = it[OrderStatusHistoryTable.comment],
                actorId = it[OrderStatusHistoryTable.actorId],
                ts = it[OrderStatusHistoryTable.ts]
            )
        }
    }
}

class WatchlistRepositoryExposed(private val tx: DatabaseTx) : WatchlistRepository {
    override suspend fun add(entry: WatchEntry): Long = tx.tx {
        WatchlistTable.insert {
            it[userId] = entry.userId
            it[itemId] = entry.itemId
            it[variantId] = entry.variantId
            it[triggerType] = entry.triggerType.name
            it[params] = entry.params
            it[createdAt] = CurrentTimestamp()
        }.requireGeneratedId(WatchlistTable.id)
    }

    override suspend fun listByUser(userId: Long): List<WatchEntry> = tx.tx {
        WatchlistTable
            .selectAll()
            .where { WatchlistTable.userId eq userId }
            .map {
                WatchEntry(
                    id = it[WatchlistTable.id],
                    userId = it[WatchlistTable.userId],
                    itemId = it[WatchlistTable.itemId],
                    variantId = it[WatchlistTable.variantId],
                    triggerType = WatchTrigger.valueOf(it[WatchlistTable.triggerType]),
                    params = it[WatchlistTable.params]
                )
            }
    }
}

private fun <T> InsertStatement<*>.requireGeneratedId(column: Column<T>): T =
    resultedValues?.singleOrNull()?.get(column)
        ?: error("Failed to obtain generated value for ${column.name}")
