package com.example.db

import com.example.db.tables.AdminUsersTable
import com.example.db.tables.AuditLogTable
import com.example.db.tables.ChannelBindingsTable
import com.example.db.tables.CartsTable
import com.example.db.tables.CartItemsTable
import com.example.db.tables.EventLogTable
import com.example.db.tables.ItemMediaTable
import com.example.db.tables.ItemsTable
import com.example.db.tables.IdempotencyKeyTable
import com.example.db.tables.LinkContextsTable
import com.example.db.tables.MerchantsTable
import com.example.db.tables.MerchantDeliveryMethodsTable
import com.example.db.tables.MerchantPaymentMethodsTable
import com.example.db.tables.OffersTable
import com.example.db.tables.OrderStatusHistoryTable
import com.example.db.tables.OrderLinesTable
import com.example.db.tables.OrdersTable
import com.example.db.tables.OrderAttachmentsTable
import com.example.db.tables.OrderDeliveryTable
import com.example.db.tables.OrderPaymentClaimsTable
import com.example.db.tables.OrderPaymentDetailsTable
import com.example.db.tables.OutboxMessageTable
import com.example.db.tables.PostsTable
import com.example.db.tables.PricesDisplayTable
import com.example.db.tables.StorefrontsTable
import com.example.db.tables.TelegramWebhookDedupTable
import com.example.db.tables.VariantsTable
import com.example.db.tables.WatchlistTable
import com.example.db.tables.BuyerDeliveryProfileTable
import com.example.domain.BargainRules
import com.example.domain.AdminRole
import com.example.domain.AdminUser
import com.example.domain.AuditLogEntry
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
import com.example.domain.MerchantDeliveryMethod
import com.example.domain.MerchantPaymentMethod
import com.example.domain.Offer
import com.example.domain.OfferStatus
import com.example.domain.Order
import com.example.domain.OrderAttachment
import com.example.domain.OrderAttachmentKind
import com.example.domain.OrderDelivery
import com.example.domain.OutboxMessage
import com.example.domain.OutboxMessageStatus
import com.example.domain.OrderLine
import com.example.domain.OrderPaymentClaim
import com.example.domain.OrderPaymentDetails
import com.example.domain.OrderStatus
import com.example.domain.OrderStatusEntry
import com.example.domain.EventLogEntry
import com.example.domain.IdempotencyKeyRecord
import com.example.domain.BuyerDeliveryProfile
import com.example.domain.PaymentClaimStatus
import com.example.domain.PaymentMethodMode
import com.example.domain.PaymentMethodType
import com.example.domain.Post
import com.example.domain.PricesDisplay
import com.example.domain.Storefront
import com.example.domain.Variant
import com.example.domain.WatchTrigger
import com.example.domain.DeliveryMethodType
import com.example.domain.watchlist.PriceDropSubscription
import com.example.domain.watchlist.RestockSubscription
import com.example.domain.watchlist.WatchlistRepository
import java.time.Instant
import kotlinx.serialization.Serializable
import kotlinx.serialization.decodeFromString
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import org.jetbrains.exposed.sql.Column
import org.jetbrains.exposed.sql.SortOrder
import org.jetbrains.exposed.sql.SqlExpressionBuilder
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greater
import org.jetbrains.exposed.sql.SqlExpressionBuilder.greaterEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.inList
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.isNotNull
import org.jetbrains.exposed.sql.SqlExpressionBuilder.less
import org.jetbrains.exposed.sql.SqlExpressionBuilder.lessEq
import org.jetbrains.exposed.sql.SqlExpressionBuilder.plus
import org.jetbrains.exposed.sql.andWhere
import org.jetbrains.exposed.sql.and
import org.jetbrains.exposed.sql.deleteWhere
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.batchInsert
import org.jetbrains.exposed.sql.select
import org.jetbrains.exposed.sql.selectAll
import org.jetbrains.exposed.sql.update
import org.jetbrains.exposed.sql.or
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.intLiteral
import org.jetbrains.exposed.sql.statements.InsertStatement
import org.jetbrains.exposed.exceptions.ExposedSQLException
import org.jetbrains.exposed.sql.ResultRow

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
            name = this[MerchantsTable.name],
            paymentClaimWindowSeconds = this[MerchantsTable.paymentClaimWindowSeconds],
            paymentReviewWindowSeconds = this[MerchantsTable.paymentReviewWindowSeconds]
        )
}

class AdminUsersRepositoryExposed(private val tx: DatabaseTx) : AdminUsersRepository {
    override suspend fun get(merchantId: String, userId: Long): AdminUser? = tx.tx {
        AdminUsersTable
            .selectAll()
            .where { (AdminUsersTable.merchantId eq merchantId) and (AdminUsersTable.userId eq userId) }
            .singleOrNull()
            ?.toAdminUser()
    }

    override suspend fun upsert(user: AdminUser) {
        tx.tx {
            val sql = """
                INSERT INTO admin_user (merchant_id, user_id, role, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (merchant_id, user_id)
                DO UPDATE SET
                    role = EXCLUDED.role,
                    updated_at = NOW()
            """.trimIndent()
            exec(
                sql,
                listOf(
                    AdminUsersTable.merchantId.columnType to user.merchantId,
                    AdminUsersTable.userId.columnType to user.userId,
                    AdminUsersTable.role.columnType to user.role.name,
                    AdminUsersTable.createdAt.columnType to user.createdAt,
                    AdminUsersTable.updatedAt.columnType to user.updatedAt
                )
            )
        }
    }

    override suspend fun listByMerchant(merchantId: String): List<AdminUser> = tx.tx {
        AdminUsersTable
            .selectAll()
            .where { AdminUsersTable.merchantId eq merchantId }
            .map { it.toAdminUser() }
    }

    private fun ResultRow.toAdminUser(): AdminUser =
        AdminUser(
            merchantId = this[AdminUsersTable.merchantId],
            userId = this[AdminUsersTable.userId],
            role = AdminRole.valueOf(this[AdminUsersTable.role]),
            createdAt = this[AdminUsersTable.createdAt],
            updatedAt = this[AdminUsersTable.updatedAt]
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

    override suspend fun upsert(storefront: Storefront) {
        tx.tx {
            val sql = """
                INSERT INTO storefronts (id, merchant_id, name, created_at)
                VALUES (?, ?, ?, NOW())
                ON CONFLICT (id)
                DO UPDATE SET
                    name = EXCLUDED.name
            """.trimIndent()
            exec(
                sql,
                listOf(
                    StorefrontsTable.id.columnType to storefront.id,
                    StorefrontsTable.merchantId.columnType to storefront.merchantId,
                    StorefrontsTable.name.columnType to storefront.name
                )
            )
        }
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

    override suspend fun upsert(storefrontId: String, channelId: Long, createdAt: Instant): Long = tx.tx {
        val sql = """
            INSERT INTO channel_bindings (storefront_id, channel_id, created_at)
            VALUES (?, ?, ?)
            ON CONFLICT (channel_id)
            DO UPDATE SET
                storefront_id = EXCLUDED.storefront_id,
                created_at = EXCLUDED.created_at
        """.trimIndent()
        exec(
            sql,
            listOf(
                ChannelBindingsTable.storefrontId.columnType to storefrontId,
                ChannelBindingsTable.channelId.columnType to channelId,
                ChannelBindingsTable.createdAt.columnType to createdAt
            )
        )
        val existing = ChannelBindingsTable
            .selectAll()
            .where {
                ChannelBindingsTable.channelId eq channelId
            }
            .single()
        existing[ChannelBindingsTable.id]
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

    override suspend fun decrementStockBatch(variantQty: Map<String, Int>): Boolean {
        if (variantQty.isEmpty()) return true
        return tx.tx {
            variantQty.forEach { (variantId, qty) ->
                require(qty > 0) { "qty must be > 0" }
                val updated = VariantsTable.update({
                    (VariantsTable.id eq variantId) and
                        (VariantsTable.stock greaterEq qty) and
                        (VariantsTable.active eq true)
                }) {
                    with(SqlExpressionBuilder) {
                        it.update(VariantsTable.stock, VariantsTable.stock - intLiteral(qty))
                    }
                }
                if (updated == 0) {
                    rollback()
                    return@tx false
                }
            }
            true
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
                it[paymentClaimedAt] = order.paymentClaimedAt
                it[paymentDecidedAt] = order.paymentDecidedAt
                it[paymentMethodType] = order.paymentMethodType?.name
                it[paymentMethodSelectedAt] = order.paymentMethodSelectedAt
                it[createdAt] = order.createdAt
                it[updatedAt] = order.updatedAt
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
                    createdAt = it[OrdersTable.createdAt],
                    updatedAt = it[OrdersTable.updatedAt],
                    paymentClaimedAt = it[OrdersTable.paymentClaimedAt],
                    paymentDecidedAt = it[OrdersTable.paymentDecidedAt],
                    paymentMethodType = it[OrdersTable.paymentMethodType]?.let(PaymentMethodType::valueOf),
                    paymentMethodSelectedAt = it[OrdersTable.paymentMethodSelectedAt]
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
                    createdAt = it[OrdersTable.createdAt],
                    updatedAt = it[OrdersTable.updatedAt],
                    paymentClaimedAt = it[OrdersTable.paymentClaimedAt],
                    paymentDecidedAt = it[OrdersTable.paymentDecidedAt],
                    paymentMethodType = it[OrdersTable.paymentMethodType]?.let(PaymentMethodType::valueOf),
                    paymentMethodSelectedAt = it[OrdersTable.paymentMethodSelectedAt]
                )
            }
    }

    override suspend fun listByMerchantAndStatus(
        merchantId: String,
        statuses: List<OrderStatus>,
        limit: Int,
        offset: Long
    ): List<Order> = tx.tx {
        OrdersTable
            .selectAll()
            .where {
                (OrdersTable.merchantId eq merchantId) and
                    (OrdersTable.status inList statuses.map { it.name })
            }
            .orderBy(OrdersTable.updatedAt to SortOrder.DESC)
            .limit(limit, offset)
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
                    createdAt = it[OrdersTable.createdAt],
                    updatedAt = it[OrdersTable.updatedAt],
                    paymentClaimedAt = it[OrdersTable.paymentClaimedAt],
                    paymentDecidedAt = it[OrdersTable.paymentDecidedAt],
                    paymentMethodType = it[OrdersTable.paymentMethodType]?.let(PaymentMethodType::valueOf),
                    paymentMethodSelectedAt = it[OrdersTable.paymentMethodSelectedAt]
                )
            }
    }

    override suspend fun setStatus(id: String, status: OrderStatus) {
        tx.tx {
            OrdersTable.update({ OrdersTable.id eq id }) {
                it[OrdersTable.status] = status.name
                if (status == OrderStatus.canceled || status == OrderStatus.PAID_CONFIRMED) {
                    it[OrdersTable.paymentDecidedAt] = CurrentTimestamp()
                }
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
                it[OrdersTable.paymentDecidedAt] = CurrentTimestamp()
                it[OrdersTable.updatedAt] = CurrentTimestamp()
            }
        }
    }

    override suspend fun setPaymentClaimed(orderId: String, claimedAt: Instant): Boolean = tx.tx {
        OrdersTable.update({
            (OrdersTable.id eq orderId) and OrdersTable.paymentClaimedAt.isNull()
        }) {
            it[paymentClaimedAt] = claimedAt
            it[updatedAt] = CurrentTimestamp()
        } > 0
    }

    override suspend fun clearPaymentClaimedAt(orderId: String): Boolean = tx.tx {
        OrdersTable.update({ OrdersTable.id eq orderId }) {
            it[paymentClaimedAt] = null
            it[updatedAt] = CurrentTimestamp()
        } > 0
    }

    override suspend fun setPaymentMethodSelection(orderId: String, type: PaymentMethodType, selectedAt: Instant): Boolean =
        tx.tx {
            OrdersTable.update({
                (OrdersTable.id eq orderId) and OrdersTable.paymentMethodType.isNull()
            }) {
                it[paymentMethodType] = type.name
                it[paymentMethodSelectedAt] = selectedAt
                it[updatedAt] = CurrentTimestamp()
            } > 0
        }

    override suspend fun listPendingClaimOlderThan(cutoff: Instant): List<Order> = tx.tx {
        OrdersTable
            .selectAll()
            .where {
                (OrdersTable.status inList listOf(
                    OrderStatus.pending.name,
                    OrderStatus.AWAITING_PAYMENT_DETAILS.name,
                    OrderStatus.AWAITING_PAYMENT.name
                )) and
                    OrdersTable.paymentClaimedAt.isNull() and
                    (OrdersTable.createdAt lessEq cutoff)
            }
            .map { it.toOrder() }
    }

    override suspend fun listPendingReviewOlderThan(cutoff: Instant): List<Order> = tx.tx {
        OrdersTable
            .selectAll()
            .where {
                (OrdersTable.status inList listOf(
                    OrderStatus.pending.name,
                    OrderStatus.PAYMENT_UNDER_REVIEW.name
                )) and
                    OrdersTable.paymentClaimedAt.isNotNull() and
                    (OrdersTable.paymentClaimedAt lessEq cutoff)
            }
            .map { it.toOrder() }
    }

    override suspend fun listPendingOlderThan(cutoff: Instant): List<Order> = tx.tx {
        OrdersTable
            .selectAll()
            .where { (OrdersTable.status eq OrderStatus.pending.name) and (OrdersTable.updatedAt lessEq cutoff) }
            .map { it.toOrder() }
    }

    private fun ResultRow.toOrder(): Order = Order(
        id = this[OrdersTable.id],
        merchantId = this[OrdersTable.merchantId],
        userId = this[OrdersTable.userId],
        itemId = this[OrdersTable.itemId],
        variantId = this[OrdersTable.variantId],
        qty = this[OrdersTable.qty],
        currency = this[OrdersTable.currency],
        amountMinor = this[OrdersTable.amountMinor],
        deliveryOption = this[OrdersTable.deliveryOption],
        addressJson = this[OrdersTable.addressJson],
        provider = this[OrdersTable.provider],
        providerChargeId = this[OrdersTable.providerChargeId],
        telegramPaymentChargeId = this[OrdersTable.telegramPaymentChargeId],
        invoiceMessageId = this[OrdersTable.invoiceMessageId],
        status = OrderStatus.valueOf(this[OrdersTable.status]),
        createdAt = this[OrdersTable.createdAt],
        updatedAt = this[OrdersTable.updatedAt],
        paymentClaimedAt = this[OrdersTable.paymentClaimedAt],
        paymentDecidedAt = this[OrdersTable.paymentDecidedAt],
        paymentMethodType = this[OrdersTable.paymentMethodType]?.let(PaymentMethodType::valueOf),
        paymentMethodSelectedAt = this[OrdersTable.paymentMethodSelectedAt]
    )
}

class OrderLinesRepositoryExposed(private val tx: DatabaseTx) : OrderLinesRepository {
    override suspend fun createBatch(lines: List<OrderLine>) {
        if (lines.isEmpty()) return
        tx.tx {
            OrderLinesTable.batchInsert(lines) { line ->
                this[OrderLinesTable.orderId] = line.orderId
                this[OrderLinesTable.listingId] = line.listingId
                this[OrderLinesTable.variantId] = line.variantId
                this[OrderLinesTable.qty] = line.qty
                this[OrderLinesTable.priceSnapshotMinor] = line.priceSnapshotMinor
                this[OrderLinesTable.currency] = line.currency
                this[OrderLinesTable.sourceStorefrontId] = line.sourceStorefrontId
                this[OrderLinesTable.sourceChannelId] = line.sourceChannelId
                this[OrderLinesTable.sourcePostMessageId] = line.sourcePostMessageId
            }
        }
    }

    override suspend fun listByOrder(orderId: String): List<OrderLine> = tx.tx {
        OrderLinesTable
            .selectAll()
            .where { OrderLinesTable.orderId eq orderId }
            .map { it.toOrderLine() }
    }

    override suspend fun listByOrders(orderIds: List<String>): Map<String, List<OrderLine>> = tx.tx {
        if (orderIds.isEmpty()) return@tx emptyMap()
        OrderLinesTable
            .selectAll()
            .where { OrderLinesTable.orderId inList orderIds }
            .map { it.toOrderLine() }
            .groupBy { it.orderId }
    }

    private fun ResultRow.toOrderLine(): OrderLine = OrderLine(
        orderId = this[OrderLinesTable.orderId],
        listingId = this[OrderLinesTable.listingId],
        variantId = this[OrderLinesTable.variantId],
        qty = this[OrderLinesTable.qty],
        priceSnapshotMinor = this[OrderLinesTable.priceSnapshotMinor],
        currency = this[OrderLinesTable.currency],
        sourceStorefrontId = this[OrderLinesTable.sourceStorefrontId],
        sourceChannelId = this[OrderLinesTable.sourceChannelId],
        sourcePostMessageId = this[OrderLinesTable.sourcePostMessageId]
    )
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

class MerchantPaymentMethodsRepositoryExposed(private val tx: DatabaseTx) : MerchantPaymentMethodsRepository {
    override suspend fun getEnabledMethod(merchantId: String, type: PaymentMethodType): MerchantPaymentMethod? = tx.tx {
        MerchantPaymentMethodsTable
            .selectAll()
            .where {
                (MerchantPaymentMethodsTable.merchantId eq merchantId) and
                    (MerchantPaymentMethodsTable.type eq type.name) and
                    (MerchantPaymentMethodsTable.enabled eq true)
            }
            .singleOrNull()
            ?.toMerchantPaymentMethod()
    }

    override suspend fun getMethod(merchantId: String, type: PaymentMethodType): MerchantPaymentMethod? = tx.tx {
        MerchantPaymentMethodsTable
            .selectAll()
            .where {
                (MerchantPaymentMethodsTable.merchantId eq merchantId) and
                    (MerchantPaymentMethodsTable.type eq type.name)
            }
            .singleOrNull()
            ?.toMerchantPaymentMethod()
    }

    override suspend fun upsert(method: MerchantPaymentMethod) {
        tx.tx {
            val sql = """
                INSERT INTO merchant_payment_method (merchant_id, type, mode, details_encrypted, enabled)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (merchant_id, type)
                DO UPDATE SET
                    mode = EXCLUDED.mode,
                    details_encrypted = EXCLUDED.details_encrypted,
                    enabled = EXCLUDED.enabled
            """.trimIndent()
            exec(
                sql,
                listOf(
                    MerchantPaymentMethodsTable.merchantId.columnType to method.merchantId,
                    MerchantPaymentMethodsTable.type.columnType to method.type.name,
                    MerchantPaymentMethodsTable.mode.columnType to method.mode.name,
                    MerchantPaymentMethodsTable.detailsEncrypted.columnType to method.detailsEncrypted,
                    MerchantPaymentMethodsTable.enabled.columnType to method.enabled
                )
            )
        }
    }

    private fun ResultRow.toMerchantPaymentMethod(): MerchantPaymentMethod =
        MerchantPaymentMethod(
            merchantId = this[MerchantPaymentMethodsTable.merchantId],
            type = PaymentMethodType.valueOf(this[MerchantPaymentMethodsTable.type]),
            mode = PaymentMethodMode.valueOf(this[MerchantPaymentMethodsTable.mode]),
            detailsEncrypted = this[MerchantPaymentMethodsTable.detailsEncrypted],
            enabled = this[MerchantPaymentMethodsTable.enabled]
        )
}

class MerchantDeliveryMethodsRepositoryExposed(private val tx: DatabaseTx) : MerchantDeliveryMethodsRepository {
    override suspend fun getEnabledMethod(
        merchantId: String,
        type: DeliveryMethodType
    ): MerchantDeliveryMethod? = tx.tx {
        MerchantDeliveryMethodsTable
            .selectAll()
            .where {
                (MerchantDeliveryMethodsTable.merchantId eq merchantId) and
                    (MerchantDeliveryMethodsTable.type eq type.name) and
                    (MerchantDeliveryMethodsTable.enabled eq true)
            }
            .singleOrNull()
            ?.toMerchantDeliveryMethod()
    }

    override suspend fun getMethod(merchantId: String, type: DeliveryMethodType): MerchantDeliveryMethod? = tx.tx {
        MerchantDeliveryMethodsTable
            .selectAll()
            .where {
                (MerchantDeliveryMethodsTable.merchantId eq merchantId) and
                    (MerchantDeliveryMethodsTable.type eq type.name)
            }
            .singleOrNull()
            ?.toMerchantDeliveryMethod()
    }

    override suspend fun upsert(method: MerchantDeliveryMethod) {
        tx.tx {
            val sql = """
                INSERT INTO merchant_delivery_method (merchant_id, type, enabled, required_fields_json)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (merchant_id, type)
                DO UPDATE SET
                    enabled = EXCLUDED.enabled,
                    required_fields_json = EXCLUDED.required_fields_json
            """.trimIndent()
            exec(
                sql,
                listOf(
                    MerchantDeliveryMethodsTable.merchantId.columnType to method.merchantId,
                    MerchantDeliveryMethodsTable.type.columnType to method.type.name,
                    MerchantDeliveryMethodsTable.enabled.columnType to method.enabled,
                    MerchantDeliveryMethodsTable.requiredFieldsJson.columnType to method.requiredFieldsJson
                )
            )
        }
    }

    private fun ResultRow.toMerchantDeliveryMethod(): MerchantDeliveryMethod =
        MerchantDeliveryMethod(
            merchantId = this[MerchantDeliveryMethodsTable.merchantId],
            type = DeliveryMethodType.valueOf(this[MerchantDeliveryMethodsTable.type]),
            enabled = this[MerchantDeliveryMethodsTable.enabled],
            requiredFieldsJson = this[MerchantDeliveryMethodsTable.requiredFieldsJson]
        )
}

class OrderPaymentDetailsRepositoryExposed(private val tx: DatabaseTx) : OrderPaymentDetailsRepository {
    override suspend fun getByOrder(orderId: String): OrderPaymentDetails? = tx.tx {
        OrderPaymentDetailsTable
            .selectAll()
            .where { OrderPaymentDetailsTable.orderId eq orderId }
            .singleOrNull()
            ?.toDetails()
    }

    override suspend fun upsert(details: OrderPaymentDetails) {
        tx.tx {
            val updated = OrderPaymentDetailsTable.update({ OrderPaymentDetailsTable.orderId eq details.orderId }) {
                it[providedByAdminId] = details.providedByAdminId
                it[text] = details.text
                it[createdAt] = details.createdAt
            }
            if (updated == 0) {
                OrderPaymentDetailsTable.insert {
                    it[orderId] = details.orderId
                    it[providedByAdminId] = details.providedByAdminId
                    it[text] = details.text
                    it[createdAt] = details.createdAt
                }
            }
        }
    }

    private fun ResultRow.toDetails(): OrderPaymentDetails =
        OrderPaymentDetails(
            orderId = this[OrderPaymentDetailsTable.orderId],
            providedByAdminId = this[OrderPaymentDetailsTable.providedByAdminId],
            text = this[OrderPaymentDetailsTable.text],
            createdAt = this[OrderPaymentDetailsTable.createdAt]
        )
}

class OrderPaymentClaimsRepositoryExposed(private val tx: DatabaseTx) : OrderPaymentClaimsRepository {
    override suspend fun getSubmittedByOrder(orderId: String): OrderPaymentClaim? = tx.tx {
        OrderPaymentClaimsTable
            .selectAll()
            .where {
                (OrderPaymentClaimsTable.orderId eq orderId) and
                    (OrderPaymentClaimsTable.status eq PaymentClaimStatus.SUBMITTED.name)
            }
            .singleOrNull()
            ?.toClaim()
    }

    override suspend fun getLatestByOrder(orderId: String): OrderPaymentClaim? = tx.tx {
        OrderPaymentClaimsTable
            .selectAll()
            .where { OrderPaymentClaimsTable.orderId eq orderId }
            .orderBy(OrderPaymentClaimsTable.createdAt to SortOrder.DESC)
            .limit(1)
            .singleOrNull()
            ?.toClaim()
    }

    override suspend fun insertClaim(claim: OrderPaymentClaim): Long = tx.tx {
        OrderPaymentClaimsTable.insert {
            it[orderId] = claim.orderId
            it[methodType] = claim.methodType.name
            it[txid] = claim.txid
            it[comment] = claim.comment
            it[createdAt] = claim.createdAt
            it[status] = claim.status.name
        }.requireGeneratedId(OrderPaymentClaimsTable.id)
    }

    override suspend fun setStatus(id: Long, status: PaymentClaimStatus, comment: String?) {
        tx.tx {
            OrderPaymentClaimsTable.update({ OrderPaymentClaimsTable.id eq id }) {
                it[OrderPaymentClaimsTable.status] = status.name
                it[OrderPaymentClaimsTable.comment] = comment
            }
        }
    }

    private fun ResultRow.toClaim(): OrderPaymentClaim =
        OrderPaymentClaim(
            id = this[OrderPaymentClaimsTable.id],
            orderId = this[OrderPaymentClaimsTable.orderId],
            methodType = PaymentMethodType.valueOf(this[OrderPaymentClaimsTable.methodType]),
            txid = this[OrderPaymentClaimsTable.txid],
            comment = this[OrderPaymentClaimsTable.comment],
            createdAt = this[OrderPaymentClaimsTable.createdAt],
            status = PaymentClaimStatus.valueOf(this[OrderPaymentClaimsTable.status])
        )
}

class OrderAttachmentsRepositoryExposed(private val tx: DatabaseTx) : OrderAttachmentsRepository {
    override suspend fun create(attachment: OrderAttachment): Long = tx.tx {
        OrderAttachmentsTable.insert {
            it[orderId] = attachment.orderId
            it[claimId] = attachment.claimId
            it[kind] = attachment.kind.name
            it[storageKey] = attachment.storageKey
            it[telegramFileId] = attachment.telegramFileId
            it[mime] = attachment.mime
            it[size] = attachment.size
            it[createdAt] = attachment.createdAt
        }.requireGeneratedId(OrderAttachmentsTable.id)
    }

    override suspend fun getById(id: Long): OrderAttachment? = tx.tx {
        OrderAttachmentsTable
            .selectAll()
            .where { OrderAttachmentsTable.id eq id }
            .singleOrNull()
            ?.toAttachment()
    }

    override suspend fun listByOrder(orderId: String): List<OrderAttachment> = tx.tx {
        OrderAttachmentsTable
            .selectAll()
            .where { OrderAttachmentsTable.orderId eq orderId }
            .map { it.toAttachment() }
    }

    override suspend fun listByOrderAndKind(orderId: String, kind: OrderAttachmentKind): List<OrderAttachment> = tx.tx {
        OrderAttachmentsTable
            .selectAll()
            .where { (OrderAttachmentsTable.orderId eq orderId) and (OrderAttachmentsTable.kind eq kind.name) }
            .map { it.toAttachment() }
    }

    override suspend fun listByClaimAndKind(claimId: Long, kind: OrderAttachmentKind): List<OrderAttachment> = tx.tx {
        OrderAttachmentsTable
            .selectAll()
            .where { (OrderAttachmentsTable.claimId eq claimId) and (OrderAttachmentsTable.kind eq kind.name) }
            .map { it.toAttachment() }
    }

    private fun ResultRow.toAttachment(): OrderAttachment =
        OrderAttachment(
            id = this[OrderAttachmentsTable.id],
            orderId = this[OrderAttachmentsTable.orderId],
            claimId = this[OrderAttachmentsTable.claimId],
            kind = OrderAttachmentKind.valueOf(this[OrderAttachmentsTable.kind]),
            storageKey = this[OrderAttachmentsTable.storageKey],
            telegramFileId = this[OrderAttachmentsTable.telegramFileId],
            mime = this[OrderAttachmentsTable.mime],
            size = this[OrderAttachmentsTable.size],
            createdAt = this[OrderAttachmentsTable.createdAt]
        )
}

class OrderDeliveryRepositoryExposed(private val tx: DatabaseTx) : OrderDeliveryRepository {
    override suspend fun getByOrder(orderId: String): OrderDelivery? = tx.tx {
        OrderDeliveryTable
            .selectAll()
            .where { OrderDeliveryTable.orderId eq orderId }
            .singleOrNull()
            ?.toOrderDelivery()
    }

    override suspend fun upsert(delivery: OrderDelivery) {
        tx.tx {
            val sql = """
                INSERT INTO order_delivery (order_id, type, fields_json, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?)
                ON CONFLICT (order_id)
                DO UPDATE SET
                    type = EXCLUDED.type,
                    fields_json = EXCLUDED.fields_json,
                    updated_at = NOW()
            """.trimIndent()
            exec(
                sql,
                listOf(
                    OrderDeliveryTable.orderId.columnType to delivery.orderId,
                    OrderDeliveryTable.type.columnType to delivery.type.name,
                    OrderDeliveryTable.fieldsJson.columnType to delivery.fieldsJson,
                    OrderDeliveryTable.createdAt.columnType to delivery.createdAt,
                    OrderDeliveryTable.updatedAt.columnType to delivery.updatedAt
                )
            )
        }
    }

    override suspend fun listByOrders(orderIds: List<String>): Map<String, OrderDelivery> = tx.tx {
        if (orderIds.isEmpty()) return@tx emptyMap()
        OrderDeliveryTable
            .selectAll()
            .where { OrderDeliveryTable.orderId inList orderIds }
            .associate { row ->
                val delivery = row.toOrderDelivery()
                delivery.orderId to delivery
            }
    }

    private fun ResultRow.toOrderDelivery(): OrderDelivery =
        OrderDelivery(
            orderId = this[OrderDeliveryTable.orderId],
            type = DeliveryMethodType.valueOf(this[OrderDeliveryTable.type]),
            fieldsJson = this[OrderDeliveryTable.fieldsJson],
            createdAt = this[OrderDeliveryTable.createdAt],
            updatedAt = this[OrderDeliveryTable.updatedAt]
        )
}

class BuyerDeliveryProfileRepositoryExposed(private val tx: DatabaseTx) : BuyerDeliveryProfileRepository {
    override suspend fun get(merchantId: String, buyerUserId: Long): BuyerDeliveryProfile? = tx.tx {
        BuyerDeliveryProfileTable
            .selectAll()
            .where {
                (BuyerDeliveryProfileTable.merchantId eq merchantId) and
                    (BuyerDeliveryProfileTable.buyerUserId eq buyerUserId)
            }
            .singleOrNull()
            ?.toProfile()
    }

    override suspend fun upsert(profile: BuyerDeliveryProfile) {
        tx.tx {
            val sql = """
                INSERT INTO buyer_delivery_profile (merchant_id, buyer_user_id, fields_json, updated_at)
                VALUES (?, ?, ?, ?)
                ON CONFLICT (merchant_id, buyer_user_id)
                DO UPDATE SET
                    fields_json = EXCLUDED.fields_json,
                    updated_at = NOW()
            """.trimIndent()
            exec(
                sql,
                listOf(
                    BuyerDeliveryProfileTable.merchantId.columnType to profile.merchantId,
                    BuyerDeliveryProfileTable.buyerUserId.columnType to profile.buyerUserId,
                    BuyerDeliveryProfileTable.fieldsJson.columnType to profile.fieldsJson,
                    BuyerDeliveryProfileTable.updatedAt.columnType to profile.updatedAt
                )
            )
        }
    }

    private fun ResultRow.toProfile(): BuyerDeliveryProfile =
        BuyerDeliveryProfile(
            merchantId = this[BuyerDeliveryProfileTable.merchantId],
            buyerUserId = this[BuyerDeliveryProfileTable.buyerUserId],
            fieldsJson = this[BuyerDeliveryProfileTable.fieldsJson],
            updatedAt = this[BuyerDeliveryProfileTable.updatedAt]
        )
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

class AuditLogRepositoryExposed(private val tx: DatabaseTx) : AuditLogRepository {
    override suspend fun insert(entry: AuditLogEntry): Long = tx.tx {
        AuditLogTable.insert {
            it[adminUserId] = entry.adminUserId
            it[action] = entry.action
            it[orderId] = entry.orderId
            it[payloadJson] = entry.payloadJson
            it[createdAt] = entry.createdAt
            it[ip] = entry.ip
            it[userAgent] = entry.userAgent
        }.requireGeneratedId(AuditLogTable.id)
    }
}

class EventLogRepositoryExposed(private val tx: DatabaseTx) : EventLogRepository {
    override suspend fun insert(entry: EventLogEntry): Long = tx.tx {
        EventLogTable.insert {
            it[ts] = entry.ts
            it[eventType] = entry.eventType
            it[buyerUserId] = entry.buyerUserId
            it[merchantId] = entry.merchantId
            it[storefrontId] = entry.storefrontId
            it[channelId] = entry.channelId
            it[postMessageId] = entry.postMessageId
            it[listingId] = entry.listingId
            it[variantId] = entry.variantId
            it[metadataJson] = entry.metadataJson
        }.requireGeneratedId(EventLogTable.id)
    }
}

class IdempotencyRepositoryExposed(private val tx: DatabaseTx) : IdempotencyRepository {
    override suspend fun findValid(
        merchantId: String,
        userId: Long,
        scope: String,
        key: String,
        validAfter: Instant
    ): IdempotencyKeyRecord? = tx.tx {
        IdempotencyKeyTable
            .selectAll()
            .where {
                (IdempotencyKeyTable.merchantId eq merchantId) and
                    (IdempotencyKeyTable.userId eq userId) and
                    (IdempotencyKeyTable.scope eq scope) and
                    (IdempotencyKeyTable.key eq key) and
                    (IdempotencyKeyTable.createdAt greaterEq validAfter)
            }
            .singleOrNull()
            ?.toIdempotencyRecord()
    }

    override suspend fun tryInsert(
        merchantId: String,
        userId: Long,
        scope: String,
        key: String,
        requestHash: String,
        createdAt: Instant
    ): Boolean = tx.tx {
        val sql = """
            INSERT INTO idempotency_key (merchant_id, user_id, scope, key, request_hash, created_at)
            VALUES (?, ?, ?, ?, ?, ?)
            ON CONFLICT (merchant_id, user_id, scope, key)
            DO NOTHING
            RETURNING 1
        """.trimIndent()
        val inserted = exec(
            sql,
            listOf(
                IdempotencyKeyTable.merchantId.columnType to merchantId,
                IdempotencyKeyTable.userId.columnType to userId,
                IdempotencyKeyTable.scope.columnType to scope,
                IdempotencyKeyTable.key.columnType to key,
                IdempotencyKeyTable.requestHash.columnType to requestHash,
                IdempotencyKeyTable.createdAt.columnType to createdAt
            )
        ) { rs -> rs.next() }
        inserted == true
    }

    override suspend fun updateResponse(
        merchantId: String,
        userId: Long,
        scope: String,
        key: String,
        responseStatus: Int,
        responseJson: String
    ) {
        tx.tx {
            IdempotencyKeyTable.update({
                (IdempotencyKeyTable.merchantId eq merchantId) and
                    (IdempotencyKeyTable.userId eq userId) and
                    (IdempotencyKeyTable.scope eq scope) and
                    (IdempotencyKeyTable.key eq key)
            }) {
                it[IdempotencyKeyTable.responseStatus] = responseStatus
                it[IdempotencyKeyTable.responseJson] = responseJson
            }
        }
    }

    override suspend fun delete(
        merchantId: String,
        userId: Long,
        scope: String,
        key: String
    ) {
        tx.tx {
            IdempotencyKeyTable.deleteWhere {
                (IdempotencyKeyTable.merchantId eq merchantId) and
                    (IdempotencyKeyTable.userId eq userId) and
                    (IdempotencyKeyTable.scope eq scope) and
                    (IdempotencyKeyTable.key eq key)
            }
        }
    }

    override suspend fun deleteIfExpired(
        merchantId: String,
        userId: Long,
        scope: String,
        key: String,
        validAfter: Instant
    ): Boolean = tx.tx {
        val deletedCount = IdempotencyKeyTable.deleteWhere {
            (IdempotencyKeyTable.merchantId eq merchantId) and
                (IdempotencyKeyTable.userId eq userId) and
                (IdempotencyKeyTable.scope eq scope) and
                (IdempotencyKeyTable.key eq key) and
                (IdempotencyKeyTable.createdAt less validAfter)
        }
        deletedCount > 0
    }

    private fun ResultRow.toIdempotencyRecord(): IdempotencyKeyRecord =
        IdempotencyKeyRecord(
            merchantId = this[IdempotencyKeyTable.merchantId],
            userId = this[IdempotencyKeyTable.userId],
            scope = this[IdempotencyKeyTable.scope],
            key = this[IdempotencyKeyTable.key],
            requestHash = this[IdempotencyKeyTable.requestHash],
            responseStatus = this[IdempotencyKeyTable.responseStatus],
            responseJson = this[IdempotencyKeyTable.responseJson],
            createdAt = this[IdempotencyKeyTable.createdAt]
        )
}


class OutboxRepositoryExposed(private val tx: DatabaseTx) : OutboxRepository {
    override suspend fun insert(type: String, payloadJson: String, now: Instant): Long = tx.tx {
        OutboxMessageTable.insert {
            it[OutboxMessageTable.type] = type
            it[OutboxMessageTable.payloadJson] = payloadJson
            it[status] = OutboxMessageStatus.NEW.name
            it[attempts] = 0
            it[nextAttemptAt] = now
            it[createdAt] = now
            it[lastError] = null
        }.requireGeneratedId(OutboxMessageTable.id)
    }

    override suspend fun fetchDueBatch(limit: Int, now: Instant): List<OutboxMessage> = tx.tx {
        if (limit <= 0) {
            return@tx emptyList()
        }
        val sql = """
            WITH due AS (
                SELECT id
                FROM outbox_message
                WHERE status = ?
                  AND next_attempt_at <= ?
                ORDER BY next_attempt_at, id
                FOR UPDATE SKIP LOCKED
                LIMIT ?
            )
            UPDATE outbox_message AS om
            SET status = ?,
                attempts = om.attempts + 1
            FROM due
            WHERE om.id = due.id
            RETURNING om.id, om.type, om.payload_json, om.status, om.attempts, om.next_attempt_at, om.created_at, om.last_error
        """.trimIndent()
        exec(
            sql,
            listOf(
                OutboxMessageTable.status.columnType to OutboxMessageStatus.NEW.name,
                OutboxMessageTable.nextAttemptAt.columnType to now,
                OutboxMessageTable.attempts.columnType to limit,
                OutboxMessageTable.status.columnType to OutboxMessageStatus.PROCESSING.name
            )
        ) { rs ->
            val messages = mutableListOf<OutboxMessage>()
            while (rs.next()) {
                messages += OutboxMessage(
                    id = rs.getLong("id"),
                    type = rs.getString("type"),
                    payloadJson = rs.getString("payload_json"),
                    status = OutboxMessageStatus.valueOf(rs.getString("status")),
                    attempts = rs.getInt("attempts"),
                    nextAttemptAt = rs.getTimestamp("next_attempt_at").toInstant(),
                    createdAt = rs.getTimestamp("created_at").toInstant(),
                    lastError = rs.getString("last_error")
                )
            }
            messages
        } ?: emptyList()
    }

    override suspend fun markDone(id: Long) {
        tx.tx {
            OutboxMessageTable.update({ OutboxMessageTable.id eq id }) {
                it[status] = OutboxMessageStatus.DONE.name
                it[lastError] = null
            }
        }
    }

    override suspend fun reschedule(id: Long, attempts: Int, nextAttemptAt: Instant, lastError: String) {
        tx.tx {
            OutboxMessageTable.update({ OutboxMessageTable.id eq id }) {
                it[status] = OutboxMessageStatus.NEW.name
                it[OutboxMessageTable.attempts] = attempts
                it[OutboxMessageTable.nextAttemptAt] = nextAttemptAt
                it[OutboxMessageTable.lastError] = lastError
            }
        }
    }

    override suspend fun markFailed(id: Long, lastError: String) {
        tx.tx {
            OutboxMessageTable.update({ OutboxMessageTable.id eq id }) {
                it[status] = OutboxMessageStatus.FAILED.name
                it[OutboxMessageTable.lastError] = lastError
            }
        }
    }

    override suspend fun countBacklog(now: Instant): Long = tx.tx {
        val count = OutboxMessageTable
            .selectAll()
            .where {
                (OutboxMessageTable.status eq OutboxMessageStatus.NEW.name) and
                    (OutboxMessageTable.nextAttemptAt lessEq now)
            }
            .count()
        count
    }
}

class TelegramWebhookDedupRepositoryExposed(private val tx: DatabaseTx) : TelegramWebhookDedupRepository {
    override suspend fun tryAcquire(
        botType: String,
        updateId: Long,
        now: Instant,
        staleBefore: Instant
    ): TelegramWebhookDedupAcquireResult = tx.tx {
        val insertSql = """
            INSERT INTO telegram_webhook_dedup (bot_type, update_id, created_at, processed_at)
            VALUES (?, ?, ?, NULL)
            ON CONFLICT (bot_type, update_id)
            DO NOTHING
            RETURNING 1
        """.trimIndent()
        val inserted = exec(
            insertSql,
            listOf(
                TelegramWebhookDedupTable.botType.columnType to botType,
                TelegramWebhookDedupTable.updateId.columnType to updateId,
                TelegramWebhookDedupTable.createdAt.columnType to now
            )
        ) { rs -> rs.next() }
        if (inserted == true) {
            return@tx TelegramWebhookDedupAcquireResult.ACQUIRED
        }

        val reacquireSql = """
            UPDATE telegram_webhook_dedup
            SET created_at = ?, processed_at = NULL
            WHERE bot_type = ?
              AND update_id = ?
              AND processed_at IS NULL
              AND created_at < ?
            RETURNING 1
        """.trimIndent()
        val reacquired = exec(
            reacquireSql,
            listOf(
                TelegramWebhookDedupTable.createdAt.columnType to now,
                TelegramWebhookDedupTable.botType.columnType to botType,
                TelegramWebhookDedupTable.updateId.columnType to updateId,
                TelegramWebhookDedupTable.createdAt.columnType to staleBefore
            )
        ) { rs -> rs.next() }
        if (reacquired == true) {
            return@tx TelegramWebhookDedupAcquireResult.ACQUIRED
        }

        val stateSql = """
            SELECT processed_at
            FROM telegram_webhook_dedup
            WHERE bot_type = ? AND update_id = ?
        """.trimIndent()
        val processed = exec(
            stateSql,
            listOf(
                TelegramWebhookDedupTable.botType.columnType to botType,
                TelegramWebhookDedupTable.updateId.columnType to updateId
            )
        ) { rs -> if (rs.next()) rs.getTimestamp("processed_at") != null else null }

        when (processed) {
            true -> TelegramWebhookDedupAcquireResult.ALREADY_PROCESSED
            false -> TelegramWebhookDedupAcquireResult.IN_PROGRESS
            null -> TelegramWebhookDedupAcquireResult.IN_PROGRESS
        }
    }

    override suspend fun markProcessed(botType: String, updateId: Long, processedAt: Instant) {
        tx.tx {
            TelegramWebhookDedupTable.update({
                (TelegramWebhookDedupTable.botType eq botType) and
                    (TelegramWebhookDedupTable.updateId eq updateId) and
                    TelegramWebhookDedupTable.processedAt.isNull()
            }) {
                it[TelegramWebhookDedupTable.processedAt] = processedAt
            }
        }
    }

    override suspend fun releaseProcessing(botType: String, updateId: Long) {
        tx.tx {
            TelegramWebhookDedupTable.deleteWhere {
                (TelegramWebhookDedupTable.botType eq botType) and
                    (TelegramWebhookDedupTable.updateId eq updateId) and
                    TelegramWebhookDedupTable.processedAt.isNull()
            }
        }
    }

    override suspend fun purge(processedBefore: Instant, staleProcessingBefore: Instant): Int = tx.tx {
        TelegramWebhookDedupTable.deleteWhere {
            ((TelegramWebhookDedupTable.processedAt.isNotNull()) and
                (TelegramWebhookDedupTable.processedAt less processedBefore)) or
                ((TelegramWebhookDedupTable.processedAt.isNull()) and
                    (TelegramWebhookDedupTable.createdAt less staleProcessingBefore))
        }
    }
}
