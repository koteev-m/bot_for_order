package com.example.db

import com.example.db.tables.ChannelBindingsTable
import com.example.db.tables.CartsTable
import com.example.db.tables.CartItemsTable
import com.example.db.tables.ItemMediaTable
import com.example.db.tables.ItemsTable
import com.example.db.tables.LinkContextsTable
import com.example.db.tables.MerchantsTable
import com.example.db.tables.OffersTable
import com.example.db.tables.OrderStatusHistoryTable
import com.example.db.tables.OrdersTable
import com.example.db.tables.PostsTable
import com.example.db.tables.PricesDisplayTable
import com.example.db.tables.StorefrontsTable
import com.example.db.tables.VariantsTable
import com.example.db.tables.WatchlistTable
import com.example.domain.BargainRules
import com.example.domain.ChannelBinding
import com.example.domain.Cart
import com.example.domain.CartItem
import com.example.domain.Item
import com.example.domain.ItemMedia
import com.example.domain.ItemStatus
import com.example.domain.LinkAction
import com.example.domain.LinkButton
import com.example.domain.LinkContext
import com.example.domain.Merchant
import com.example.domain.Offer
import com.example.domain.OfferStatus
import com.example.domain.Order
import com.example.domain.OrderStatus
import com.example.domain.OrderStatusEntry
import com.example.domain.Post
import com.example.domain.PricesDisplay
import com.example.domain.Storefront
import com.example.domain.Variant
import com.example.domain.WatchTrigger
import com.example.domain.watchlist.PriceDropSubscription
import com.example.domain.watchlist.RestockSubscription
import com.example.domain.watchlist.WatchlistRepository
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.ResultRow
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.intLiteral
import org.jetbrains.exposed.sql.statements.InsertStatement
import org.jetbrains.exposed.exceptions.ExposedSQLException

private val json = Json

class MerchantsRepositoryExposed(private val tx: DatabaseTx) : MerchantsRepository {
    override suspend fun getById(id: String): Merchant? = tx.tx {
        MerchantsTable
            .selectAll()
            .where { MerchantsTable.id eq id }
            .singleOrNull()
            ?.toMerchant()
    }

    private fun ResultRow.toMerchant(): Merchant =
        Merchant(
            id = this[MerchantsTable.id],
            name = this[MerchantsTable.name]
        )
}

class StorefrontsRepositoryExposed(private val tx: DatabaseTx) : StorefrontsRepository {
    override suspend fun create(storefront: Storefront) {
        tx.tx {
            StorefrontsTable.insert {
                it[id] = storefront.id
                it[merchantId] = storefront.merchantId
                it[name] = storefront.name
                it[createdAt] = CurrentTimestamp()
            }
        }
    }

    override suspend fun getById(id: String): Storefront? = tx.tx {
        StorefrontsTable
            .selectAll()
            .where { StorefrontsTable.id eq id }
            .singleOrNull()
            ?.toStorefront()
    }

    override suspend fun listByMerchant(merchantId: String): List<Storefront> = tx.tx {
        StorefrontsTable
            .selectAll()
            .where { StorefrontsTable.merchantId eq merchantId }
            .map { it.toStorefront() }
    }

    private fun ResultRow.toStorefront(): Storefront =
        Storefront(
            id = this[StorefrontsTable.id],
            merchantId = this[StorefrontsTable.merchantId],
            name = this[StorefrontsTable.name]
        )
}

class ChannelBindingsRepositoryExposed(private val tx: DatabaseTx) : ChannelBindingsRepository {
    override suspend fun bind(storefrontId: String, channelId: Long, createdAt: Instant): Long = tx.tx {
        ChannelBindingsTable.insert {
            it[ChannelBindingsTable.storefrontId] = storefrontId
            it[ChannelBindingsTable.channelId] = channelId
            it[ChannelBindingsTable.createdAt] = createdAt
        }.requireGeneratedId(ChannelBindingsTable.id)
    }

    override suspend fun getByChannel(channelId: Long): ChannelBinding? = tx.tx {
        ChannelBindingsTable
            .selectAll()
            .where { ChannelBindingsTable.channelId eq channelId }
            .singleOrNull()
            ?.toChannelBinding()
    }

    override suspend fun listByStorefront(storefrontId: String): List<ChannelBinding> = tx.tx {
        ChannelBindingsTable
            .selectAll()
            .where { ChannelBindingsTable.storefrontId eq storefrontId }
            .map { it.toChannelBinding() }
    }

    private fun ResultRow.toChannelBinding(): ChannelBinding =
        ChannelBinding(
            id = this[ChannelBindingsTable.id],
            storefrontId = this[ChannelBindingsTable.storefrontId],
            channelId = this[ChannelBindingsTable.channelId],
            createdAt = this[ChannelBindingsTable.createdAt]
        )
}

class LinkContextsRepositoryExposed(private val tx: DatabaseTx) : LinkContextsRepository {
    override suspend fun create(context: LinkContext): Long = tx.tx {
        LinkContextsTable.insert {
            it[tokenHash] = context.tokenHash
            it[merchantId] = context.merchantId
            it[storefrontId] = context.storefrontId
            it[channelId] = context.channelId
            it[postMessageId] = context.postMessageId
            it[listingId] = context.listingId
            it[action] = context.action.name
            it[button] = context.button.name
            it[createdAt] = context.createdAt
            it[revokedAt] = context.revokedAt
            it[expiresAt] = context.expiresAt
            it[metadataJson] = context.metadataJson
        }.requireGeneratedId(LinkContextsTable.id)
    }

    override suspend fun getByTokenHash(tokenHash: String): LinkContext? = tx.tx {
        LinkContextsTable
            .selectAll()
            .where { LinkContextsTable.tokenHash eq tokenHash }
            .singleOrNull()
            ?.toLinkContext()
    }

    override suspend fun revokeByTokenHash(tokenHash: String, revokedAt: Instant): Boolean = tx.tx {
        LinkContextsTable.update({
            (LinkContextsTable.tokenHash eq tokenHash) and LinkContextsTable.revokedAt.isNull()
        }) {
            it[LinkContextsTable.revokedAt] = revokedAt
        } > 0
    }

    private fun ResultRow.toLinkContext(): LinkContext =
        LinkContext(
            id = this[LinkContextsTable.id],
            tokenHash = this[LinkContextsTable.tokenHash],
            merchantId = this[LinkContextsTable.merchantId],
            storefrontId = this[LinkContextsTable.storefrontId],
            channelId = this[LinkContextsTable.channelId],
            postMessageId = this[LinkContextsTable.postMessageId],
            listingId = this[LinkContextsTable.listingId],
            action = LinkAction.valueOf(this[LinkContextsTable.action]),
            button = LinkButton.valueOf(this[LinkContextsTable.button]),
            createdAt = this[LinkContextsTable.createdAt],
            revokedAt = this[LinkContextsTable.revokedAt],
            expiresAt = this[LinkContextsTable.expiresAt],
            metadataJson = this[LinkContextsTable.metadataJson]
        )
}

class ItemsRepositoryExposed(private val tx: DatabaseTx) : ItemsRepository {
    override suspend fun create(item: Item) {
        tx.tx {
            ItemsTable.insert {
                it[id] = item.id
                it[merchantId] = item.merchantId
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
            merchantId = this[ItemsTable.merchantId],
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

    override suspend fun setStock(variantId: String, stock: Int): StockChange? = tx.tx {
        val row = VariantsTable
            .select { VariantsTable.id eq variantId }
            .forUpdate()
            .singleOrNull()
            ?: return@tx null
        val previous = row[VariantsTable.stock]
        VariantsTable.update({ VariantsTable.id eq variantId }) {
            it[VariantsTable.stock] = stock
        }
        StockChange(
            variantId = variantId,
            itemId = row[VariantsTable.itemId],
            oldStock = previous,
            newStock = stock
        )
    }

    override suspend fun getById(id: String): Variant? = tx.tx {
        VariantsTable
            .selectAll()
            .where { VariantsTable.id eq id }
            .singleOrNull()
            ?.let {
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

    override suspend fun decrementStock(variantId: String, qty: Int): Boolean {
        require(qty > 0) { "qty must be > 0" }
        return tx.tx {
            val updated = VariantsTable.update({
                (VariantsTable.id eq variantId) and (VariantsTable.stock greaterEq qty)
            }) {
                with(SqlExpressionBuilder) {
                    it.update(VariantsTable.stock, VariantsTable.stock - intLiteral(qty))
                }
            }
            updated > 0
        }
    }
}

class PricesDisplayRepositoryExposed(private val tx: DatabaseTx) : PricesDisplayRepository {
    override suspend fun upsert(p: PricesDisplay) {
        tx.tx {
            val updated = PricesDisplayTable.update({ PricesDisplayTable.itemId eq p.itemId }) {
                it[baseCurrency] = p.baseCurrency
                it[baseAmountMinor] = p.baseAmountMinor
                it[invoiceAmountMinor] = p.invoiceCurrencyAmountMinor
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
                    it[invoiceAmountMinor] = p.invoiceCurrencyAmountMinor
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
                    invoiceCurrencyAmountMinor = it[PricesDisplayTable.invoiceAmountMinor],
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
            it[merchantId] = post.merchantId
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
                    merchantId = it[PostsTable.merchantId],
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
                it[expiresAt] = offer.expiresAt
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
            ?.toOffer()
    }

    override suspend fun findActiveByUserAndItem(
        userId: Long,
        itemId: String,
        variantId: String?
    ): Offer? = tx.tx {
        val now = Instant.now()
        val variantExpr = variantId?.let { OffersTable.variantId eq it } ?: OffersTable.variantId.isNull()
        OffersTable
            .selectAll()
            .where { OffersTable.userId eq userId }
            .andWhere { OffersTable.itemId eq itemId }
            .andWhere { variantExpr }
            .andWhere { OffersTable.status inList ACTIVE_STATUS_NAMES }
            .andWhere { OffersTable.expiresAt.isNull() or (OffersTable.expiresAt greater now) }
            .orderBy(OffersTable.createdAt to SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.toOffer()
    }

    override suspend fun updateStatusAndCounters(
        id: String,
        status: OfferStatus,
        countersUsed: Int,
        lastCounterAmount: Long?,
        expiresAt: Instant?
    ) {
        tx.tx {
            OffersTable.update({ OffersTable.id eq id }) {
                it[OffersTable.status] = status.name
                it[OffersTable.countersUsed] = countersUsed
                it[OffersTable.lastCounterAmount] = lastCounterAmount
                it[OffersTable.expiresAt] = expiresAt
                it[OffersTable.updatedAt] = CurrentTimestamp()
            }
        }
    }

    override suspend fun updateCounter(id: String, amountMinor: Long, expiresAt: Instant) {
        tx.tx {
            OffersTable.update({ OffersTable.id eq id }) {
                it[OffersTable.status] = OfferStatus.countered.name
                it[OffersTable.lastCounterAmount] = amountMinor
                it[OffersTable.expiresAt] = expiresAt
                with(SqlExpressionBuilder) {
                    it.update(OffersTable.countersUsed, OffersTable.countersUsed + intLiteral(1))
                }
                it[OffersTable.updatedAt] = CurrentTimestamp()
            }
        }
    }

    override suspend fun expireWhereDue(now: Instant): Int = tx.tx {
        OffersTable.update({
            OffersTable.expiresAt.isNotNull()
                .and(OffersTable.expiresAt lessEq now)
                .and(OffersTable.status inList ACTIVE_STATUS_NAMES)
        }) {
            it[OffersTable.status] = OfferStatus.expired.name
            it[OffersTable.updatedAt] = CurrentTimestamp()
        }
    }
}

private val ACTIVE_STATUS_NAMES = listOf(
    OfferStatus.new.name,
    OfferStatus.auto_accept.name,
    OfferStatus.countered.name
)

private fun ResultRow.toOffer(): Offer = Offer(
    id = this[OffersTable.id],
    itemId = this[OffersTable.itemId],
    variantId = this[OffersTable.variantId],
    userId = this[OffersTable.userId],
    offerAmountMinor = this[OffersTable.offerAmountMinor],
    status = OfferStatus.valueOf(this[OffersTable.status]),
    countersUsed = this[OffersTable.countersUsed],
    expiresAt = this[OffersTable.expiresAt],
    lastCounterAmount = this[OffersTable.lastCounterAmount]
)

class OrdersRepositoryExposed(private val tx: DatabaseTx) : OrdersRepository {
    override suspend fun create(order: Order) {
        tx.tx {
            OrdersTable.insert {
                it[id] = order.id
                it[merchantId] = order.merchantId
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
                    merchantId = it[OrdersTable.merchantId],
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
                    merchantId = it[OrdersTable.merchantId],
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

    override suspend fun listPendingOlderThan(cutoff: Instant): List<Order> = tx.tx {
        OrdersTable
            .selectAll()
            .where { (OrdersTable.status eq OrderStatus.pending.name) and (OrdersTable.updatedAt lessEq cutoff) }
            .map {
                Order(
                    id = it[OrdersTable.id],
                    merchantId = it[OrdersTable.merchantId],
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

class CartsRepositoryExposed(private val tx: DatabaseTx) : CartsRepository {
    override suspend fun getByMerchantAndBuyer(merchantId: String, buyerUserId: Long): Cart? = tx.tx {
        CartsTable
            .selectAll()
            .where { (CartsTable.merchantId eq merchantId) and (CartsTable.buyerUserId eq buyerUserId) }
            .singleOrNull()
            ?.toCart()
    }

    override suspend fun getOrCreate(merchantId: String, buyerUserId: Long, now: Instant): Cart = tx.tx {
        CartsTable
            .selectAll()
            .where { (CartsTable.merchantId eq merchantId) and (CartsTable.buyerUserId eq buyerUserId) }
            .singleOrNull()
            ?.toCart()
            ?: run {
                val id = try {
                    CartsTable.insert {
                        it[CartsTable.merchantId] = merchantId
                        it[CartsTable.buyerUserId] = buyerUserId
                        it[CartsTable.createdAt] = now
                        it[CartsTable.updatedAt] = now
                    }.requireGeneratedId(CartsTable.id)
                } catch (e: ExposedSQLException) {
                    if (e.sqlState == "23505") {
                        return@tx CartsTable
                            .selectAll()
                            .where { (CartsTable.merchantId eq merchantId) and (CartsTable.buyerUserId eq buyerUserId) }
                            .single()
                            .toCart()
                    }
                    throw e
                }
                Cart(
                    id = id,
                    merchantId = merchantId,
                    buyerUserId = buyerUserId,
                    createdAt = now,
                    updatedAt = now
                )
            }
    }

    override suspend fun touch(cartId: Long, now: Instant) {
        tx.tx {
            CartsTable.update({ CartsTable.id eq cartId }) {
                it[CartsTable.updatedAt] = now
            }
        }
    }

    private fun ResultRow.toCart(): Cart =
        Cart(
            id = this[CartsTable.id],
            merchantId = this[CartsTable.merchantId],
            buyerUserId = this[CartsTable.buyerUserId],
            createdAt = this[CartsTable.createdAt],
            updatedAt = this[CartsTable.updatedAt]
        )
}

class CartItemsRepositoryExposed(private val tx: DatabaseTx) : CartItemsRepository {
    override suspend fun listByCart(cartId: Long): List<CartItem> = tx.tx {
        CartItemsTable
            .selectAll()
            .where { CartItemsTable.cartId eq cartId }
            .orderBy(CartItemsTable.createdAt to SortOrder.ASC)
            .map { it.toCartItem() }
    }

    override suspend fun getById(id: Long): CartItem? = tx.tx {
        CartItemsTable
            .selectAll()
            .where { CartItemsTable.id eq id }
            .singleOrNull()
            ?.toCartItem()
    }

    override suspend fun create(item: CartItem): Long = tx.tx {
        CartItemsTable.insert {
            it[cartId] = item.cartId
            it[listingId] = item.listingId
            it[variantId] = item.variantId
            it[qty] = item.qty
            it[priceSnapshotMinor] = item.priceSnapshotMinor
            it[currency] = item.currency
            it[sourceStorefrontId] = item.sourceStorefrontId
            it[sourceChannelId] = item.sourceChannelId
            it[sourcePostMessageId] = item.sourcePostMessageId
            it[createdAt] = item.createdAt
        }.requireGeneratedId(CartItemsTable.id)
    }

    override suspend fun updateQty(lineId: Long, qty: Int) {
        tx.tx {
            CartItemsTable.update({ CartItemsTable.id eq lineId }) {
                it[CartItemsTable.qty] = qty
            }
        }
    }

    override suspend fun updateVariant(lineId: Long, variantId: String?, priceSnapshotMinor: Long, currency: String) {
        tx.tx {
            CartItemsTable.update({ CartItemsTable.id eq lineId }) {
                it[CartItemsTable.variantId] = variantId
                it[CartItemsTable.priceSnapshotMinor] = priceSnapshotMinor
                it[CartItemsTable.currency] = currency
            }
        }
    }

    override suspend fun delete(lineId: Long): Boolean = tx.tx {
        CartItemsTable.deleteWhere { CartItemsTable.id eq lineId } > 0
    }

    override suspend fun getLineWithCart(lineId: Long): CartItemWithCart? = tx.tx {
        CartItemsTable
            .innerJoin(CartsTable)
            .select { CartItemsTable.id eq lineId }
            .singleOrNull()
            ?.let { row ->
                CartItemWithCart(
                    item = row.toCartItem(),
                    cart = row.toCart()
                )
            }
    }

    private fun ResultRow.toCartItem(): CartItem =
        CartItem(
            id = this[CartItemsTable.id],
            cartId = this[CartItemsTable.cartId],
            listingId = this[CartItemsTable.listingId],
            variantId = this[CartItemsTable.variantId],
            qty = this[CartItemsTable.qty],
            priceSnapshotMinor = this[CartItemsTable.priceSnapshotMinor],
            currency = this[CartItemsTable.currency],
            sourceStorefrontId = this[CartItemsTable.sourceStorefrontId],
            sourceChannelId = this[CartItemsTable.sourceChannelId],
            sourcePostMessageId = this[CartItemsTable.sourcePostMessageId],
            createdAt = this[CartItemsTable.createdAt]
        )

    private fun ResultRow.toCart(): Cart =
        Cart(
            id = this[CartsTable.id],
            merchantId = this[CartsTable.merchantId],
            buyerUserId = this[CartsTable.buyerUserId],
            createdAt = this[CartsTable.createdAt],
            updatedAt = this[CartsTable.updatedAt]
        )
}

class WatchlistRepositoryExposed(private val tx: DatabaseTx) : WatchlistRepository {
    override suspend fun upsertPriceDrop(sub: PriceDropSubscription) {
        tx.tx {
            val paramsJson = json.encodeToString(PriceDropParams(sub.targetMinor))
            val updated = WatchlistTable.update({
                (WatchlistTable.userId eq sub.userId) and
                    (WatchlistTable.itemId eq sub.itemId) and
                    (WatchlistTable.triggerType eq WatchTrigger.PRICE_DROP.name)
            }) {
                it[params] = paramsJson
            }
            if (updated == 0) {
                WatchlistTable.insert {
                    it[userId] = sub.userId
                    it[itemId] = sub.itemId
                    it[variantId] = null
                    it[triggerType] = WatchTrigger.PRICE_DROP.name
                    it[params] = paramsJson
                    it[createdAt] = CurrentTimestamp()
                }
            }
        }
    }

    override suspend fun deletePriceDrop(userId: Long, itemId: String) {
        tx.tx {
            WatchlistTable.deleteWhere {
                (WatchlistTable.userId eq userId) and
                    (WatchlistTable.itemId eq itemId) and
                    (WatchlistTable.triggerType eq WatchTrigger.PRICE_DROP.name)
            }
        }
    }

    override suspend fun listPriceDropByItem(itemId: String): List<PriceDropSubscription> = tx.tx {
        WatchlistTable
            .selectAll()
            .where {
                (WatchlistTable.itemId eq itemId) and
                    (WatchlistTable.triggerType eq WatchTrigger.PRICE_DROP.name)
            }
            .map {
                PriceDropSubscription(
                    userId = it[WatchlistTable.userId],
                    itemId = it[WatchlistTable.itemId],
                    targetMinor = parsePriceDropParams(it[WatchlistTable.params]).targetMinor
                )
            }
    }

    override suspend fun upsertRestock(sub: RestockSubscription) {
        tx.tx {
            val updated = WatchlistTable.update({
                (WatchlistTable.userId eq sub.userId) and
                    (WatchlistTable.itemId eq sub.itemId) and
                    (WatchlistTable.triggerType eq WatchTrigger.RESTOCK.name) and
                    restockVariantCondition(sub.variantId)
            }) {
                it[params] = null
            }
            if (updated == 0) {
                WatchlistTable.insert {
                    it[userId] = sub.userId
                    it[itemId] = sub.itemId
                    it[variantId] = sub.variantId
                    it[triggerType] = WatchTrigger.RESTOCK.name
                    it[params] = null
                    it[createdAt] = CurrentTimestamp()
                }
            }
        }
    }

    override suspend fun deleteRestock(userId: Long, itemId: String, variantId: String?) {
        tx.tx {
            WatchlistTable.deleteWhere {
                (WatchlistTable.userId eq userId) and
                    (WatchlistTable.itemId eq itemId) and
                    (WatchlistTable.triggerType eq WatchTrigger.RESTOCK.name) and
                    restockVariantCondition(variantId)
            }
        }
    }

    override suspend fun listRestockByItemVariant(
        itemId: String,
        variantId: String?
    ): List<RestockSubscription> = tx.tx {
        WatchlistTable
            .selectAll()
            .where {
                (WatchlistTable.itemId eq itemId) and
                    (WatchlistTable.triggerType eq WatchTrigger.RESTOCK.name) and
                    restockVariantCondition(variantId)
            }
            .map { it.toRestockSubscription() }
    }

    override suspend fun listRestockSubscriptions(): List<RestockSubscription> = tx.tx {
        WatchlistTable
            .selectAll()
            .where { WatchlistTable.triggerType eq WatchTrigger.RESTOCK.name }
            .map { it.toRestockSubscription() }
    }

    @Serializable
    private data class PriceDropParams(val targetMinor: Long?)

    private fun parsePriceDropParams(payload: String?): PriceDropParams {
        if (payload.isNullOrBlank()) {
            return PriceDropParams(targetMinor = null)
        }
        return runCatching { json.decodeFromString<PriceDropParams>(payload) }
            .getOrElse { PriceDropParams(targetMinor = null) }
    }
    private fun restockVariantCondition(variantId: String?) = with(SqlExpressionBuilder) {
        if (variantId == null) {
            WatchlistTable.variantId.isNull()
        } else {
            WatchlistTable.variantId eq variantId
        }
    }

    private fun ResultRow.toRestockSubscription(): RestockSubscription = RestockSubscription(
        userId = this[WatchlistTable.userId],
        itemId = this[WatchlistTable.itemId],
        variantId = this[WatchlistTable.variantId]
    )
}

private fun <T> InsertStatement<*>.requireGeneratedId(column: Column<T>): T =
    resultedValues?.singleOrNull()?.get(column)
        ?: error("Failed to obtain generated value for ${column.name}")
