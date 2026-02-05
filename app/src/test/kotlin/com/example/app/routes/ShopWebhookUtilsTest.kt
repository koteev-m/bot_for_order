package com.example.app.routes

import com.example.app.baseTestConfig
import com.example.app.testutil.InMemoryVariantsRepository
import com.example.db.ItemsRepository
import com.example.db.MerchantsRepository
import com.example.db.OrderLinesRepository
import com.example.db.OrderStatusHistoryRepository
import com.example.db.OrdersRepository
import com.example.db.PricesDisplayRepository
import com.example.db.VariantsRepository
import com.example.db.TelegramWebhookDedupRepository
import com.example.domain.Order
import com.example.domain.OrderStatus
import com.example.domain.Variant
import com.example.domain.hold.HoldService
import com.example.domain.hold.LockManager
import com.example.domain.hold.OrderHoldService
import com.example.bots.TelegramClients
import com.example.app.services.PaymentsService
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.mockk.mockk
import java.time.Instant
import kotlinx.serialization.json.Json

class ShopWebhookUtilsTest : StringSpec({
    "decrementStock uses legacy fields when lines are empty" {
        val variantsRepository = InMemoryVariantsRepository()
        variantsRepository.upsert(
            Variant(
                id = "variant-1",
                itemId = "item-1",
                size = null,
                sku = null,
                stock = 5,
                active = true
            )
        )
        val deps = buildDeps(variantsRepository)
        val order = buildOrder(variantId = "variant-1", qty = 2)

        val updated = decrementStock(order, emptyList(), deps)

        updated shouldBe true
        variantsRepository.getById("variant-1")!!.stock shouldBe 3
    }

    "decrementStock returns false on insufficient stock for legacy fields" {
        val variantsRepository = InMemoryVariantsRepository()
        variantsRepository.upsert(
            Variant(
                id = "variant-2",
                itemId = "item-2",
                size = null,
                sku = null,
                stock = 1,
                active = true
            )
        )
        val deps = buildDeps(variantsRepository)
        val order = buildOrder(variantId = "variant-2", qty = 2)

        val updated = decrementStock(order, emptyList(), deps)

        updated shouldBe false
        variantsRepository.getById("variant-2")!!.stock shouldBe 1
    }
})

private fun buildDeps(variantsRepository: VariantsRepository): ShopWebhookDeps = ShopWebhookDeps(
    config = baseTestConfig(),
    clients = mockk<TelegramClients>(relaxed = true),
    itemsRepository = mockk<ItemsRepository>(relaxed = true),
    pricesRepository = mockk<PricesDisplayRepository>(relaxed = true),
    variantsRepository = variantsRepository,
    ordersRepository = mockk<OrdersRepository>(relaxed = true),
    orderLinesRepository = mockk<OrderLinesRepository>(relaxed = true),
    merchantsRepository = mockk<MerchantsRepository>(relaxed = true),
    orderStatusRepository = mockk<OrderStatusHistoryRepository>(relaxed = true),
    paymentsService = mockk<PaymentsService>(relaxed = true),
    lockManager = mockk<LockManager>(relaxed = true),
    orderHoldService = mockk<OrderHoldService>(relaxed = true),
    holdService = mockk<HoldService>(relaxed = true),
    webhookDedupRepository = mockk<TelegramWebhookDedupRepository>(relaxed = true),
    json = Json { ignoreUnknownKeys = true }
)

private fun buildOrder(variantId: String, qty: Int): Order = Order(
    id = "order-1",
    merchantId = "merchant-1",
    userId = 100L,
    itemId = "item-1",
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
    status = OrderStatus.paid,
    createdAt = Instant.EPOCH,
    updatedAt = Instant.EPOCH,
    paymentClaimedAt = null,
    paymentDecidedAt = null
)
