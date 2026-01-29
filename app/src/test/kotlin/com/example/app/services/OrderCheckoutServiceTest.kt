package com.example.app.services

import com.example.app.baseTestConfig
import com.example.app.api.ApiError
import com.example.app.testutil.InMemoryHoldService
import com.example.app.testutil.InMemoryOrderDedupStore
import com.example.app.testutil.InMemoryOrderHoldService
import com.example.app.testutil.InMemoryEventLogRepository
import com.example.app.testutil.NoopLockManager
import com.example.app.testutil.isDockerAvailable
import com.example.db.CartItemsRepositoryExposed
import com.example.db.CartsRepositoryExposed
import com.example.db.DatabaseFactory
import com.example.db.DatabaseTx
import com.example.db.MerchantsRepositoryExposed
import com.example.db.OrderLinesRepositoryExposed
import com.example.db.OrderStatusHistoryRepositoryExposed
import com.example.db.OrdersRepositoryExposed
import com.example.db.ItemsRepositoryExposed
import com.example.db.VariantsRepositoryExposed
import com.example.domain.CartItem
import com.example.domain.Item
import com.example.domain.ItemStatus
import com.example.domain.Order
import com.example.domain.OrderStatus
import com.example.domain.Variant
import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import kotlinx.coroutines.runBlocking
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assumptions
import org.testcontainers.containers.PostgreSQLContainer
import io.ktor.server.engine.embeddedServer
import io.ktor.server.netty.Netty

class OrderCheckoutServiceTest : StringSpec({
    val dockerAvailable = isDockerAvailable()
    var dockerReady = dockerAvailable
    val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
    var started = false
    var dataSource: HikariDataSource? = null
    lateinit var dbTx: DatabaseTx
    lateinit var cartsRepository: CartsRepositoryExposed
    lateinit var cartItemsRepository: CartItemsRepositoryExposed
    lateinit var merchantsRepository: MerchantsRepositoryExposed
    lateinit var ordersRepository: OrdersRepositoryExposed
    lateinit var orderLinesRepository: OrderLinesRepositoryExposed
    lateinit var orderStatusHistoryRepository: OrderStatusHistoryRepositoryExposed
    lateinit var itemsRepository: ItemsRepositoryExposed
    lateinit var variantsRepository: VariantsRepositoryExposed

    beforeSpec {
        if (!dockerAvailable) {
            return@beforeSpec
        }
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
            cartsRepository = CartsRepositoryExposed(dbTx)
            cartItemsRepository = CartItemsRepositoryExposed(dbTx)
            merchantsRepository = MerchantsRepositoryExposed(dbTx)
            ordersRepository = OrdersRepositoryExposed(dbTx)
            orderLinesRepository = OrderLinesRepositoryExposed(dbTx)
            orderStatusHistoryRepository = OrderStatusHistoryRepositoryExposed(dbTx)
            itemsRepository = ItemsRepositoryExposed(dbTx)
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

    "creates multi-line order from cart and clears cart".config(enabled = dockerAvailable) {
        Assumptions.assumeTrue(dockerReady, "Docker недоступен или контейнер не стартовал.")
        val cfg = baseTestConfig()
        val holdService = InMemoryOrderHoldService()
        val orderCheckoutService = OrderCheckoutService(
            config = cfg,
            dbTx = dbTx,
            cartsRepository = cartsRepository,
            cartItemsRepository = cartItemsRepository,
            merchantsRepository = merchantsRepository,
            variantsRepository = variantsRepository,
            ordersRepository = ordersRepository,
            orderLinesRepository = orderLinesRepository,
            eventLogRepository = InMemoryEventLogRepository(),
            orderHoldService = holdService,
            lockManager = NoopLockManager(),
            orderDedupStore = InMemoryOrderDedupStore()
        )

        val item1 = Item(
            id = "item-${UUID.randomUUID()}",
            merchantId = cfg.merchants.defaultMerchantId,
            title = "Item 1",
            description = "Desc",
            status = ItemStatus.active,
            allowBargain = false,
            bargainRules = null
        )
        val item2 = Item(
            id = "item-${UUID.randomUUID()}",
            merchantId = cfg.merchants.defaultMerchantId,
            title = "Item 2",
            description = "Desc",
            status = ItemStatus.active,
            allowBargain = false,
            bargainRules = null
        )
        itemsRepository.create(item1)
        itemsRepository.create(item2)

        val variant = Variant(
            id = "var-${UUID.randomUUID()}",
            itemId = item1.id,
            size = "M",
            sku = "sku",
            stock = 5,
            active = true
        )
        variantsRepository.upsert(variant)

        val buyerId = 101L
        val now = Instant.now()
        val cart = cartsRepository.getOrCreate(cfg.merchants.defaultMerchantId, buyerId, now)

        cartItemsRepository.create(
            CartItem(
                id = 0,
                cartId = cart.id,
                listingId = item1.id,
                variantId = variant.id,
                qty = 2,
                priceSnapshotMinor = 100,
                currency = "USD",
                sourceStorefrontId = "storefront",
                sourceChannelId = 1L,
                sourcePostMessageId = 10,
                createdAt = now
            )
        )
        cartItemsRepository.create(
            CartItem(
                id = 0,
                cartId = cart.id,
                listingId = item2.id,
                variantId = null,
                qty = 1,
                priceSnapshotMinor = 200,
                currency = "USD",
                sourceStorefrontId = "storefront",
                sourceChannelId = 1L,
                sourcePostMessageId = 10,
                createdAt = now
            )
        )

        val result = orderCheckoutService.createFromCart(buyerId, now)
        result.lines shouldHaveSize 2
        result.order.amountMinor shouldBe 400

        val storedLines = orderLinesRepository.listByOrder(result.order.id)
        storedLines shouldHaveSize 2
        cartItemsRepository.listByCart(cart.id) shouldHaveSize 0
    }

    "hold conflict blocks second order".config(enabled = dockerAvailable) {
        Assumptions.assumeTrue(dockerReady, "Docker недоступен или контейнер не стартовал.")
        val cfg = baseTestConfig()
        val holdService = InMemoryOrderHoldService()
        val orderCheckoutService = OrderCheckoutService(
            config = cfg,
            dbTx = dbTx,
            cartsRepository = cartsRepository,
            cartItemsRepository = cartItemsRepository,
            merchantsRepository = merchantsRepository,
            variantsRepository = variantsRepository,
            ordersRepository = ordersRepository,
            orderLinesRepository = orderLinesRepository,
            eventLogRepository = InMemoryEventLogRepository(),
            orderHoldService = holdService,
            lockManager = NoopLockManager(),
            orderDedupStore = InMemoryOrderDedupStore()
        )

        val item = Item(
            id = "item-${UUID.randomUUID()}",
            merchantId = cfg.merchants.defaultMerchantId,
            title = "Item",
            description = "Desc",
            status = ItemStatus.active,
            allowBargain = false,
            bargainRules = null
        )
        itemsRepository.create(item)

        val variant = Variant(
            id = "var-${UUID.randomUUID()}",
            itemId = item.id,
            size = "M",
            sku = "sku",
            stock = 5,
            active = true
        )
        variantsRepository.upsert(variant)

        val now = Instant.now()
        val cartA = cartsRepository.getOrCreate(cfg.merchants.defaultMerchantId, 201L, now)
        cartItemsRepository.create(
            CartItem(
                id = 0,
                cartId = cartA.id,
                listingId = item.id,
                variantId = variant.id,
                qty = 1,
                priceSnapshotMinor = 100,
                currency = "USD",
                sourceStorefrontId = "storefront",
                sourceChannelId = 1L,
                sourcePostMessageId = 10,
                createdAt = now
            )
        )
        orderCheckoutService.createFromCart(201L, now)

        val cartB = cartsRepository.getOrCreate(cfg.merchants.defaultMerchantId, 202L, now)
        cartItemsRepository.create(
            CartItem(
                id = 0,
                cartId = cartB.id,
                listingId = item.id,
                variantId = variant.id,
                qty = 1,
                priceSnapshotMinor = 100,
                currency = "USD",
                sourceStorefrontId = "storefront",
                sourceChannelId = 1L,
                sourcePostMessageId = 10,
                createdAt = now
            )
        )

        val error = runCatching { orderCheckoutService.createFromCart(202L, now) }.exceptionOrNull()
        error shouldNotBe null
        error!!.shouldBeInstanceOf<ApiError>()
    }

    "auto-cancel on claim and review timeout".config(enabled = dockerAvailable) {
        Assumptions.assumeTrue(dockerReady, "Docker недоступен или контейнер не стартовал.")
        val cfg = baseTestConfig()
        val now = Instant.parse("2024-01-01T00:00:00Z")
        val holdService = InMemoryOrderHoldService { now }
        val clock = Clock.fixed(now, ZoneOffset.UTC)

        val legacyItem = Item(
            id = "legacy-item",
            merchantId = cfg.merchants.defaultMerchantId,
            title = "Legacy",
            description = "Desc",
            status = ItemStatus.active,
            allowBargain = false,
            bargainRules = null
        )
        val legacyItem2 = legacyItem.copy(id = "legacy-item-2")
        itemsRepository.create(legacyItem)
        itemsRepository.create(legacyItem2)

        val order = Order(
            id = "ord-claim-${UUID.randomUUID()}",
            merchantId = cfg.merchants.defaultMerchantId,
            userId = 401L,
            itemId = "legacy-item",
            variantId = null,
            qty = 1,
            currency = "USD",
            amountMinor = 100,
            deliveryOption = null,
            addressJson = null,
            provider = null,
            providerChargeId = null,
            telegramPaymentChargeId = null,
            invoiceMessageId = null,
            status = OrderStatus.pending,
            createdAt = now.minusSeconds(400),
            updatedAt = now.minusSeconds(400)
        )
        ordersRepository.create(order)
        orderLinesRepository.createBatch(
            listOf(
                com.example.domain.OrderLine(
                    orderId = order.id,
                    listingId = order.itemId ?: "legacy-item",
                    variantId = null,
                    qty = 1,
                    priceSnapshotMinor = 100,
                    currency = "USD",
                    sourceStorefrontId = null,
                    sourceChannelId = null,
                    sourcePostMessageId = null
                )
            )
        )
        holdService.tryAcquire(
            order.id,
            listOf(com.example.domain.hold.OrderHoldRequest(order.itemId ?: "legacy-item", null, 1)),
            300
        )

        val deps = ReservesSweepDeps(
            ordersRepository = ordersRepository,
            orderLinesRepository = orderLinesRepository,
            merchantsRepository = merchantsRepository,
            historyRepository = orderStatusHistoryRepository,
            orderHoldService = holdService,
            holdService = InMemoryHoldService { now },
            clients = com.example.bots.TelegramClients("token", "token", null),
            sendBuyerNotifications = false,
            sweepIntervalSec = 1,
            clock = clock
        )
        val server = embeddedServer(Netty, port = 0) { }
        val job = ReservesSweepJob(application = server.application, deps = deps)
        runBlocking { job.runOnceForTests() }

        val canceled = ordersRepository.get(order.id)
        canceled?.status shouldBe OrderStatus.canceled

        val reviewOrder = Order(
            id = "ord-review-${UUID.randomUUID()}",
            merchantId = cfg.merchants.defaultMerchantId,
            userId = 402L,
            itemId = "legacy-item-2",
            variantId = null,
            qty = 1,
            currency = "USD",
            amountMinor = 100,
            deliveryOption = null,
            addressJson = null,
            provider = null,
            providerChargeId = null,
            telegramPaymentChargeId = null,
            invoiceMessageId = null,
            status = OrderStatus.pending,
            createdAt = now.minusSeconds(1000),
            updatedAt = now.minusSeconds(1000),
            paymentClaimedAt = now.minusSeconds(1000)
        )
        ordersRepository.create(reviewOrder)
        orderLinesRepository.createBatch(
            listOf(
                com.example.domain.OrderLine(
                    orderId = reviewOrder.id,
                    listingId = reviewOrder.itemId ?: "legacy-item-2",
                    variantId = null,
                    qty = 1,
                    priceSnapshotMinor = 100,
                    currency = "USD",
                    sourceStorefrontId = null,
                    sourceChannelId = null,
                    sourcePostMessageId = null
                )
            )
        )
        holdService.tryAcquire(
            reviewOrder.id,
            listOf(com.example.domain.hold.OrderHoldRequest(reviewOrder.itemId ?: "legacy-item-2", null, 1)),
            900
        )
        runBlocking { job.runOnceForTests() }
        val canceledReview = ordersRepository.get(reviewOrder.id)
        canceledReview?.status shouldBe OrderStatus.canceled
    }
})
