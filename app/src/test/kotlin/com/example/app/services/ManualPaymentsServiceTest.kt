package com.example.app.services

import com.example.app.baseTestConfig
import com.example.app.api.ApiError
import com.example.app.testutil.FakeManualPaymentsNotifier
import com.example.app.testutil.InMemoryHoldService
import com.example.app.testutil.InMemoryOrderHoldService
import com.example.app.testutil.InMemoryStorage
import com.example.app.testutil.InMemoryEventLogRepository
import com.example.app.testutil.NoopLockManager
import com.example.app.testutil.isDockerAvailable
import com.example.db.DatabaseFactory
import com.example.db.DatabaseTx
import com.example.db.MerchantPaymentMethodsRepositoryExposed
import com.example.db.MerchantsRepositoryExposed
import com.example.db.OrderAttachmentsRepositoryExposed
import com.example.db.OrderLinesRepositoryExposed
import com.example.db.OrderPaymentClaimsRepositoryExposed
import com.example.db.OrderPaymentDetailsRepositoryExposed
import com.example.db.OrderStatusHistoryRepositoryExposed
import com.example.db.OrdersRepositoryExposed
import com.example.db.VariantsRepositoryExposed
import com.example.db.tables.ItemsTable
import com.example.db.tables.MerchantPaymentMethodsTable
import com.example.db.tables.MerchantsTable
import com.example.db.tables.OrderPaymentClaimsTable
import com.example.db.tables.VariantsTable
import com.example.domain.ItemStatus
import com.example.domain.MerchantPaymentMethod
import com.example.domain.Order
import com.example.domain.OrderAttachmentKind
import com.example.domain.OrderLine
import com.example.domain.OrderStatus
import com.example.domain.PaymentClaimStatus
import com.example.domain.PaymentMethodMode
import com.example.domain.PaymentMethodType
import com.example.domain.hold.OrderHoldRequest
import com.example.domain.hold.ReserveSource
import com.example.domain.hold.StockReservePayload
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assumptions
import org.testcontainers.containers.PostgreSQLContainer
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp
import org.jetbrains.exposed.sql.SqlExpressionBuilder.eq
import org.jetbrains.exposed.sql.selectAll

class ManualPaymentsServiceTest : StringSpec({
    val dockerAvailable = isDockerAvailable()
    var dockerReady = dockerAvailable
    val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
    var started = false
    var dataSource: HikariDataSource? = null
    lateinit var dbTx: DatabaseTx
    lateinit var merchantsRepository: MerchantsRepositoryExposed
    lateinit var ordersRepository: OrdersRepositoryExposed
    lateinit var orderLinesRepository: OrderLinesRepositoryExposed
    lateinit var orderStatusHistoryRepository: OrderStatusHistoryRepositoryExposed
    lateinit var paymentMethodsRepository: MerchantPaymentMethodsRepositoryExposed
    lateinit var paymentDetailsRepository: OrderPaymentDetailsRepositoryExposed
    lateinit var paymentClaimsRepository: OrderPaymentClaimsRepositoryExposed
    lateinit var attachmentsRepository: OrderAttachmentsRepositoryExposed
    lateinit var variantsRepository: VariantsRepositoryExposed

    beforeSpec {
        if (!dockerAvailable) return@beforeSpec
        try {
            postgres.start()
            started = true
            val initializedDataSource = DatabaseFactory.createHikari(
                url = postgres.jdbcUrl,
                user = postgres.username,
                password = postgres.password
            )
            dataSource = initializedDataSource
            Flyway.configure()
                .dataSource(initializedDataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate()
            DatabaseFactory.connect(initializedDataSource)
            dbTx = DatabaseTx()
            merchantsRepository = MerchantsRepositoryExposed(dbTx)
            ordersRepository = OrdersRepositoryExposed(dbTx)
            orderLinesRepository = OrderLinesRepositoryExposed(dbTx)
            orderStatusHistoryRepository = OrderStatusHistoryRepositoryExposed(dbTx)
            paymentMethodsRepository = MerchantPaymentMethodsRepositoryExposed(dbTx)
            paymentDetailsRepository = OrderPaymentDetailsRepositoryExposed(dbTx)
            paymentClaimsRepository = OrderPaymentClaimsRepositoryExposed(dbTx)
            attachmentsRepository = OrderAttachmentsRepositoryExposed(dbTx)
            variantsRepository = VariantsRepositoryExposed(dbTx)
            dockerReady = true
        } catch (e: Exception) {
            dockerReady = false
            dataSource?.close()
            if (started) {
                postgres.stop()
                started = false
            }
        }
    }

    afterSpec {
        dataSource?.close()
        if (started) {
            postgres.stop()
        }
    }

    fun insertMerchant(id: String, claimWindow: Int = 300, reviewWindow: Int = 900) = runBlocking {
        dbTx.tx {
            MerchantsTable.insert {
                it[MerchantsTable.id] = id
                it[MerchantsTable.name] = "Test"
                it[MerchantsTable.paymentClaimWindowSeconds] = claimWindow
                it[MerchantsTable.paymentReviewWindowSeconds] = reviewWindow
                it[MerchantsTable.createdAt] = CurrentTimestamp()
            }
        }
    }

    fun newMerchantId(): String = "m-${UUID.randomUUID()}"

    fun insertItemAndVariant(merchantId: String, variantId: String, stock: Int) = runBlocking {
        dbTx.tx {
            ItemsTable.insert {
                it[id] = "item-$variantId"
                it[ItemsTable.merchantId] = merchantId
                it[title] = "Item"
                it[description] = "Desc"
                it[status] = ItemStatus.active.name
                it[allowBargain] = false
                it[bargainRulesJson] = null
                it[createdAt] = CurrentTimestamp()
                it[updatedAt] = CurrentTimestamp()
            }
            VariantsTable.insert {
                it[id] = variantId
                it[itemId] = "item-$variantId"
                it[size] = "M"
                it[sku] = "sku"
                it[VariantsTable.stock] = stock
                it[active] = true
            }
        }
    }

    suspend fun insertPaymentMethod(method: MerchantPaymentMethod) {
        dbTx.tx {
            MerchantPaymentMethodsTable.insert {
                it[merchantId] = method.merchantId
                it[type] = method.type.name
                it[mode] = method.mode.name
                it[detailsEncrypted] = method.detailsEncrypted
                it[enabled] = method.enabled
            }
        }
    }

    suspend fun createOrder(
        merchantId: String,
        buyerId: Long,
        variantId: String,
        qty: Int,
        now: Instant = Instant.now()
    ): Order {
        val orderId = "ord-${UUID.randomUUID()}"
        val order = Order(
            id = orderId,
            merchantId = merchantId,
            userId = buyerId,
            itemId = "item-$variantId",
            variantId = variantId,
            qty = qty,
            currency = "USD",
            amountMinor = 1000,
            deliveryOption = null,
            addressJson = null,
            provider = null,
            providerChargeId = null,
            telegramPaymentChargeId = null,
            invoiceMessageId = null,
            status = OrderStatus.pending,
            createdAt = now,
            updatedAt = now
        )
        ordersRepository.create(order)
        orderLinesRepository.createBatch(
            listOf(
                OrderLine(
                    orderId = orderId,
                    listingId = "item-$variantId",
                    variantId = variantId,
                    qty = qty,
                    priceSnapshotMinor = 1000,
                    currency = "USD",
                    sourceStorefrontId = null,
                    sourceChannelId = null,
                    sourcePostMessageId = null
                )
            )
        )
        return order
    }

    "claim -> confirm -> paid_confirmed and stock decrement".config(enabled = dockerAvailable) {
        Assumptions.assumeTrue(dockerReady, "Docker недоступен или контейнер не стартовал.")
        val cfg = baseTestConfig()
        val crypto = PaymentDetailsCrypto(cfg.manualPayments.detailsEncryptionKey)
        val merchantId = newMerchantId()
        insertMerchant(merchantId)
        val variantId = "var-${UUID.randomUUID()}"
        insertItemAndVariant(merchantId, variantId, stock = 5)
        val method = MerchantPaymentMethod(
            merchantId = merchantId,
            type = PaymentMethodType.MANUAL_CARD,
            mode = PaymentMethodMode.AUTO,
            detailsEncrypted = crypto.encrypt("Pay to card"),
            enabled = true
        )
        insertPaymentMethod(method)

        val order = createOrder(merchantId, 10L, variantId, qty = 2)
        val holdService = InMemoryOrderHoldService()
        val holdRequests = listOf(OrderHoldRequest(listingId = "item-$variantId", variantId = variantId, qty = 2))
        runBlocking { holdService.tryAcquire(order.id, holdRequests, 300) }

        val service = ManualPaymentsService(
            dbTx,
            ordersRepository,
            orderLinesRepository,
            orderStatusHistoryRepository,
            merchantsRepository,
            paymentMethodsRepository,
            paymentDetailsRepository,
            paymentClaimsRepository,
            attachmentsRepository,
            variantsRepository,
            holdService,
            InMemoryHoldService(),
            NoopLockManager(),
            InMemoryEventLogRepository(),
            InMemoryStorage(),
            crypto,
            FakeManualPaymentsNotifier(),
            Clock.systemUTC()
        )

        runBlocking {
            service.selectPaymentMethod(order.id, order.userId, method.type)
            service.submitClaim(order.id, order.userId, txid = "tx123", comment = null, attachments = emptyList())
            val confirmed = service.confirmPayment(order.id, adminId = 99L)
            confirmed.status shouldBe OrderStatus.PAID_CONFIRMED
            variantsRepository.getById(variantId)?.stock shouldBe 3
            holdService.hasActive(order.id, holdRequests) shouldBe false
        }
    }

    "reject keeps order awaiting and extends holds".config(enabled = dockerAvailable) {
        Assumptions.assumeTrue(dockerReady, "Docker недоступен или контейнер не стартовал.")
        val cfg = baseTestConfig()
        val clock = MutableClock(Instant.parse("2024-01-01T00:00:00Z"))
        val crypto = PaymentDetailsCrypto(cfg.manualPayments.detailsEncryptionKey)
        val merchantId = newMerchantId()
        insertMerchant(merchantId, claimWindow = 60, reviewWindow = 120)
        val variantId = "var-${UUID.randomUUID()}"
        insertItemAndVariant(merchantId, variantId, stock = 5)
        val method = MerchantPaymentMethod(
            merchantId = merchantId,
            type = PaymentMethodType.MANUAL_CRYPTO,
            mode = PaymentMethodMode.AUTO,
            detailsEncrypted = crypto.encrypt("Wallet"),
            enabled = true
        )
        insertPaymentMethod(method)
        val order = createOrder(merchantId, 11L, variantId, qty = 1, now = clock.instant())

        val holdService = InMemoryOrderHoldService { clock.instant() }
        val holdRequests = listOf(OrderHoldRequest(listingId = "item-$variantId", variantId = variantId, qty = 1))
        runBlocking { holdService.tryAcquire(order.id, holdRequests, 60) }

        val service = ManualPaymentsService(
            dbTx,
            ordersRepository,
            orderLinesRepository,
            orderStatusHistoryRepository,
            merchantsRepository,
            paymentMethodsRepository,
            paymentDetailsRepository,
            paymentClaimsRepository,
            attachmentsRepository,
            variantsRepository,
            holdService,
            InMemoryHoldService { clock.instant() },
            NoopLockManager(),
            InMemoryEventLogRepository(),
            InMemoryStorage(),
            crypto,
            FakeManualPaymentsNotifier(),
            clock
        )

        runBlocking {
            service.selectPaymentMethod(order.id, order.userId, method.type)
            service.submitClaim(order.id, order.userId, txid = "tx-check", comment = "check", attachments = emptyList())
            clock.advanceSeconds(30)
            val rejected = service.rejectPayment(order.id, adminId = 1L, reason = "no_match")
            rejected.status shouldBe OrderStatus.AWAITING_PAYMENT
            clock.advanceSeconds(20)
            holdService.hasActive(order.id, holdRequests) shouldBe true
            clock.advanceSeconds(20)
            holdService.hasActive(order.id, holdRequests) shouldBe false
        }
    }

    "crypto claim without txid returns txid_required".config(enabled = dockerAvailable) {
        Assumptions.assumeTrue(dockerReady, "Docker недоступен или контейнер не стартовал.")
        val cfg = baseTestConfig()
        val crypto = PaymentDetailsCrypto(cfg.manualPayments.detailsEncryptionKey)
        val merchantId = newMerchantId()
        insertMerchant(merchantId)
        val variantId = "var-${UUID.randomUUID()}"
        insertItemAndVariant(merchantId, variantId, stock = 5)
        val method = MerchantPaymentMethod(
            merchantId = merchantId,
            type = PaymentMethodType.MANUAL_CRYPTO,
            mode = PaymentMethodMode.AUTO,
            detailsEncrypted = crypto.encrypt("Wallet"),
            enabled = true
        )
        insertPaymentMethod(method)
        val order = createOrder(merchantId, 15L, variantId, qty = 1)

        val service = ManualPaymentsService(
            dbTx,
            ordersRepository,
            orderLinesRepository,
            orderStatusHistoryRepository,
            merchantsRepository,
            paymentMethodsRepository,
            paymentDetailsRepository,
            paymentClaimsRepository,
            attachmentsRepository,
            variantsRepository,
            InMemoryOrderHoldService(),
            InMemoryHoldService(),
            NoopLockManager(),
            InMemoryEventLogRepository(),
            InMemoryStorage(),
            crypto,
            FakeManualPaymentsNotifier(),
            Clock.systemUTC()
        )

        runBlocking {
            service.selectPaymentMethod(order.id, order.userId, method.type)
            val error = runCatching {
                service.submitClaim(order.id, order.userId, txid = "   ", comment = "check", attachments = emptyList())
            }.exceptionOrNull() as ApiError
            error.message shouldBe "txid_required"
            error.status shouldBe io.ktor.http.HttpStatusCode.BadRequest
        }
    }

    "submit claim is idempotent under review before input validation".config(enabled = dockerAvailable) {
        Assumptions.assumeTrue(dockerReady, "Docker недоступен или контейнер не стартовал.")
        val cfg = baseTestConfig()
        val crypto = PaymentDetailsCrypto(cfg.manualPayments.detailsEncryptionKey)
        val merchantId = newMerchantId()
        insertMerchant(merchantId)
        val variantId = "var-${UUID.randomUUID()}"
        insertItemAndVariant(merchantId, variantId, stock = 5)
        val method = MerchantPaymentMethod(
            merchantId = merchantId,
            type = PaymentMethodType.MANUAL_CRYPTO,
            mode = PaymentMethodMode.AUTO,
            detailsEncrypted = crypto.encrypt("Wallet"),
            enabled = true
        )
        insertPaymentMethod(method)
        val order = createOrder(merchantId, 16L, variantId, qty = 1)

        val service = ManualPaymentsService(
            dbTx,
            ordersRepository,
            orderLinesRepository,
            orderStatusHistoryRepository,
            merchantsRepository,
            paymentMethodsRepository,
            paymentDetailsRepository,
            paymentClaimsRepository,
            attachmentsRepository,
            variantsRepository,
            InMemoryOrderHoldService(),
            InMemoryHoldService(),
            NoopLockManager(),
            InMemoryEventLogRepository(),
            InMemoryStorage(),
            crypto,
            FakeManualPaymentsNotifier(),
            Clock.systemUTC()
        )

        runBlocking {
            service.selectPaymentMethod(order.id, order.userId, method.type)
            val first = service.submitClaim(
                order.id,
                order.userId,
                txid = "tx-valid",
                comment = "comment",
                attachments = emptyList()
            )
            ordersRepository.get(order.id)!!.status shouldBe OrderStatus.PAYMENT_UNDER_REVIEW

            val repeated = service.submitClaim(
                order.id,
                order.userId,
                txid = "   ",
                comment = "   ",
                attachments = emptyList()
            )

            repeated.id shouldBe first.id
            repeated.txid shouldBe first.txid
            repeated.comment shouldBe first.comment
            repeated.status shouldBe first.status
        }
    }

    "manual_send details transition and instructions".config(enabled = dockerAvailable) {
        Assumptions.assumeTrue(dockerReady, "Docker недоступен или контейнер не стартовал.")
        val cfg = baseTestConfig()
        val crypto = PaymentDetailsCrypto(cfg.manualPayments.detailsEncryptionKey)
        val merchantId = newMerchantId()
        insertMerchant(merchantId)
        val variantId = "var-${UUID.randomUUID()}"
        insertItemAndVariant(merchantId, variantId, stock = 5)
        val method = MerchantPaymentMethod(
            merchantId = merchantId,
            type = PaymentMethodType.MANUAL_CARD,
            mode = PaymentMethodMode.MANUAL_SEND,
            detailsEncrypted = null,
            enabled = true
        )
        insertPaymentMethod(method)
        val order = createOrder(merchantId, 12L, variantId, qty = 1)

        val service = ManualPaymentsService(
            dbTx,
            ordersRepository,
            orderLinesRepository,
            orderStatusHistoryRepository,
            merchantsRepository,
            paymentMethodsRepository,
            paymentDetailsRepository,
            paymentClaimsRepository,
            attachmentsRepository,
            variantsRepository,
            InMemoryOrderHoldService(),
            InMemoryHoldService(),
            NoopLockManager(),
            InMemoryEventLogRepository(),
            InMemoryStorage(),
            crypto,
            FakeManualPaymentsNotifier(),
            Clock.systemUTC()
        )

        runBlocking {
            val selected = service.selectPaymentMethod(order.id, order.userId, method.type)
            selected.status shouldBe OrderStatus.AWAITING_PAYMENT_DETAILS
            service.getPaymentInstructions(order.id, order.userId).text shouldBe "ожидаем реквизиты"
            service.setPaymentDetails(order.id, adminId = 5L, text = "Send 1 BTC")
            val after = ordersRepository.get(order.id)!!
            after.status shouldBe OrderStatus.AWAITING_PAYMENT
            service.getPaymentInstructions(order.id, order.userId).text shouldBe "Send 1 BTC"
        }
    }

    "claim stores attachments".config(enabled = dockerAvailable) {
        Assumptions.assumeTrue(dockerReady, "Docker недоступен или контейнер не стартовал.")
        val cfg = baseTestConfig()
        val crypto = PaymentDetailsCrypto(cfg.manualPayments.detailsEncryptionKey)
        val merchantId = newMerchantId()
        insertMerchant(merchantId)
        val variantId = "var-${UUID.randomUUID()}"
        insertItemAndVariant(merchantId, variantId, stock = 5)
        val method = MerchantPaymentMethod(
            merchantId = merchantId,
            type = PaymentMethodType.MANUAL_CRYPTO,
            mode = PaymentMethodMode.AUTO,
            detailsEncrypted = crypto.encrypt("Wallet"),
            enabled = true
        )
        insertPaymentMethod(method)
        val order = createOrder(merchantId, 13L, variantId, qty = 1)

        val storage = InMemoryStorage()
        val service = ManualPaymentsService(
            dbTx,
            ordersRepository,
            orderLinesRepository,
            orderStatusHistoryRepository,
            merchantsRepository,
            paymentMethodsRepository,
            paymentDetailsRepository,
            paymentClaimsRepository,
            attachmentsRepository,
            variantsRepository,
            InMemoryOrderHoldService(),
            InMemoryHoldService(),
            NoopLockManager(),
            InMemoryEventLogRepository(),
            storage,
            crypto,
            FakeManualPaymentsNotifier(),
            Clock.systemUTC()
        )

        runBlocking {
            service.selectPaymentMethod(order.id, order.userId, method.type)
            service.submitClaim(
                order.id,
                order.userId,
                txid = "hash",
                comment = null,
                attachments = listOf(
                    PaymentClaimAttachment(
                        filename = "proof.png",
                        contentType = "image/png",
                        bytes = ByteArray(10) { 1 }
                    )
                )
            )
            val attachments = attachmentsRepository.listByOrderAndKind(order.id, OrderAttachmentKind.PAYMENT_PROOF)
            attachments shouldHaveSize 1
            storage.objects.containsKey(attachments.first().storageKey) shouldBe true
        }
    }

    "reject allows resubmit and refreshes paymentClaimedAt".config(enabled = dockerAvailable) {
        Assumptions.assumeTrue(dockerReady, "Docker недоступен или контейнер не стартовал.")
        val cfg = baseTestConfig()
        val clock = MutableClock(Instant.parse("2024-02-01T00:00:00Z"))
        val crypto = PaymentDetailsCrypto(cfg.manualPayments.detailsEncryptionKey)
        val merchantId = newMerchantId()
        insertMerchant(merchantId, claimWindow = 120, reviewWindow = 120)
        val variantId = "var-${UUID.randomUUID()}"
        insertItemAndVariant(merchantId, variantId, stock = 2)
        val method = MerchantPaymentMethod(
            merchantId = merchantId,
            type = PaymentMethodType.MANUAL_CRYPTO,
            mode = PaymentMethodMode.AUTO,
            detailsEncrypted = crypto.encrypt("Wallet"),
            enabled = true
        )
        insertPaymentMethod(method)
        val order = createOrder(merchantId, 20L, variantId, qty = 1, now = clock.instant())

        val service = ManualPaymentsService(
            dbTx,
            ordersRepository,
            orderLinesRepository,
            orderStatusHistoryRepository,
            merchantsRepository,
            paymentMethodsRepository,
            paymentDetailsRepository,
            paymentClaimsRepository,
            attachmentsRepository,
            variantsRepository,
            InMemoryOrderHoldService { clock.instant() },
            InMemoryHoldService { clock.instant() },
            NoopLockManager(),
            InMemoryEventLogRepository(),
            InMemoryStorage(),
            crypto,
            FakeManualPaymentsNotifier(),
            clock
        )

        runBlocking {
            service.selectPaymentMethod(order.id, order.userId, method.type)
            service.submitClaim(order.id, order.userId, txid = "tx-first", comment = "first", attachments = emptyList())
            val firstClaimedAt = ordersRepository.get(order.id)!!.paymentClaimedAt!!
            clock.advanceSeconds(10)
            service.rejectPayment(order.id, adminId = 1L, reason = "no_match")
            ordersRepository.get(order.id)!!.paymentClaimedAt shouldBe null
            clock.advanceSeconds(5)
            val secondClaim = service.submitClaim(order.id, order.userId, txid = "tx-second", comment = "second", attachments = emptyList())
            val refreshed = ordersRepository.get(order.id)!!.paymentClaimedAt
            secondClaim.status shouldBe PaymentClaimStatus.SUBMITTED
            refreshed shouldBe clock.instant()
            refreshed shouldBe firstClaimedAt.plusSeconds(15)
        }
    }

    "confirm stock mismatch rejects claim and cancels order".config(enabled = dockerAvailable) {
        Assumptions.assumeTrue(dockerReady, "Docker недоступен или контейнер не стартовал.")
        val cfg = baseTestConfig()
        val clock = MutableClock(Instant.parse("2024-03-01T00:00:00Z"))
        val crypto = PaymentDetailsCrypto(cfg.manualPayments.detailsEncryptionKey)
        val merchantId = newMerchantId()
        insertMerchant(merchantId)
        val variantId = "var-${UUID.randomUUID()}"
        insertItemAndVariant(merchantId, variantId, stock = 0)
        val method = MerchantPaymentMethod(
            merchantId = merchantId,
            type = PaymentMethodType.MANUAL_CARD,
            mode = PaymentMethodMode.AUTO,
            detailsEncrypted = crypto.encrypt("Pay"),
            enabled = true
        )
        insertPaymentMethod(method)
        val order = createOrder(merchantId, 30L, variantId, qty = 1, now = clock.instant())

        val orderHoldService = InMemoryOrderHoldService { clock.instant() }
        val holdRequests = listOf(OrderHoldRequest(listingId = "item-$variantId", variantId = variantId, qty = 1))
        runBlocking { orderHoldService.tryAcquire(order.id, holdRequests, 300) }
        val holdService = InMemoryHoldService { clock.instant() }
        runBlocking {
            holdService.createOrderReserve(
                order.id,
                StockReservePayload(
                    itemId = "item-$variantId",
                    variantId = variantId,
                    qty = 1,
                    userId = order.userId,
                    ttlSec = 300,
                    from = ReserveSource.ORDER
                ),
                ttlSec = 300
            )
        }

        val service = ManualPaymentsService(
            dbTx,
            ordersRepository,
            orderLinesRepository,
            orderStatusHistoryRepository,
            merchantsRepository,
            paymentMethodsRepository,
            paymentDetailsRepository,
            paymentClaimsRepository,
            attachmentsRepository,
            variantsRepository,
            orderHoldService,
            holdService,
            NoopLockManager(),
            InMemoryEventLogRepository(),
            InMemoryStorage(),
            crypto,
            FakeManualPaymentsNotifier(),
            clock
        )

        runBlocking {
            service.selectPaymentMethod(order.id, order.userId, method.type)
            service.submitClaim(order.id, order.userId, txid = null, comment = "claim", attachments = emptyList())
            runCatching { service.confirmPayment(order.id, adminId = 99L) }
            ordersRepository.get(order.id)!!.status shouldBe OrderStatus.canceled
            orderHoldService.hasActive(order.id, holdRequests) shouldBe false
            holdService.hasOrderReserve(order.id) shouldBe false
            val status = dbTx.tx {
                OrderPaymentClaimsTable
                    .selectAll()
                    .where { OrderPaymentClaimsTable.orderId eq order.id }
                    .single()[OrderPaymentClaimsTable.status]
            }
            status shouldBe PaymentClaimStatus.REJECTED.name
        }
    }
})

private class MutableClock(
    private var current: Instant
) : Clock() {
    override fun getZone(): ZoneOffset = ZoneOffset.UTC
    override fun withZone(zone: java.time.ZoneId): Clock = this
    override fun instant(): Instant = current
    fun advanceSeconds(seconds: Long) {
        current = current.plusSeconds(seconds)
    }
}
