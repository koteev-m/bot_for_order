package com.example.app.services

import com.example.app.baseTestConfig
import com.example.app.testutil.isDockerAvailable
import com.example.db.BuyerDeliveryProfileRepositoryExposed
import com.example.db.DatabaseFactory
import com.example.db.DatabaseTx
import com.example.db.MerchantDeliveryMethodsRepositoryExposed
import com.example.db.OrderDeliveryRepositoryExposed
import com.example.db.OrdersRepositoryExposed
import com.example.db.tables.MerchantDeliveryMethodsTable
import com.example.db.tables.MerchantsTable
import com.example.domain.DeliveryMethodType
import com.example.domain.Order
import com.example.domain.OrderStatus
import com.example.app.api.ApiError
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.assertions.throwables.shouldThrow
import java.time.Instant
import java.util.UUID
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assumptions
import org.testcontainers.containers.PostgreSQLContainer
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import org.jetbrains.exposed.sql.insert
import org.jetbrains.exposed.sql.javatime.CurrentTimestamp

class DeliveryServiceTest : StringSpec({
    val dockerAvailable = isDockerAvailable()
    var dockerReady = dockerAvailable
    val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
    var started = false
    var dataSource: HikariDataSource? = null
    lateinit var dbTx: DatabaseTx
    lateinit var ordersRepository: OrdersRepositoryExposed
    lateinit var deliveryMethodsRepository: MerchantDeliveryMethodsRepositoryExposed
    lateinit var orderDeliveryRepository: OrderDeliveryRepositoryExposed
    lateinit var buyerDeliveryProfileRepository: BuyerDeliveryProfileRepositoryExposed

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
            ordersRepository = OrdersRepositoryExposed(dbTx)
            deliveryMethodsRepository = MerchantDeliveryMethodsRepositoryExposed(dbTx)
            orderDeliveryRepository = OrderDeliveryRepositoryExposed(dbTx)
            buyerDeliveryProfileRepository = BuyerDeliveryProfileRepositoryExposed(dbTx)
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

    fun insertMerchant(id: String) = runBlocking {
        dbTx.tx {
            MerchantsTable.insert {
                it[MerchantsTable.id] = id
                it[MerchantsTable.name] = "Test"
                it[MerchantsTable.paymentClaimWindowSeconds] = 300
                it[MerchantsTable.paymentReviewWindowSeconds] = 900
                it[MerchantsTable.createdAt] = CurrentTimestamp()
            }
        }
    }

    suspend fun insertDeliveryMethod(merchantId: String, requiredFieldsJson: String) {
        dbTx.tx {
            MerchantDeliveryMethodsTable.insert {
                it[MerchantDeliveryMethodsTable.merchantId] = merchantId
                it[MerchantDeliveryMethodsTable.type] = DeliveryMethodType.CDEK_PICKUP_MANUAL.name
                it[MerchantDeliveryMethodsTable.enabled] = true
                it[MerchantDeliveryMethodsTable.requiredFieldsJson] = requiredFieldsJson
            }
        }
    }

    suspend fun createOrder(merchantId: String, buyerId: Long): Order {
        val now = Instant.now()
        val order = Order(
            id = "ord-${UUID.randomUUID()}",
            merchantId = merchantId,
            userId = buyerId,
            itemId = null,
            variantId = null,
            qty = null,
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
        return order
    }

    "required delivery fields validation".config(enabled = dockerAvailable) {
        Assumptions.assumeTrue(dockerReady, "Docker недоступен или контейнер не стартовал.")
        val cfg = baseTestConfig()
        val merchantId = "m-${UUID.randomUUID()}"
        insertMerchant(merchantId)
        insertDeliveryMethod(merchantId, "[\"pvzCode\",\"pvzAddress\"]")
        val order = createOrder(merchantId, 10L)

        val service = DeliveryService(
            cfg,
            ordersRepository,
            deliveryMethodsRepository,
            orderDeliveryRepository,
            buyerDeliveryProfileRepository
        )

        val error = shouldThrow<ApiError> {
            runBlocking {
                service.setOrderDelivery(
                    order.id,
                    order.userId,
                    JsonObject(mapOf("pvzCode" to JsonPrimitive("123")))
                )
            }
        }
        error.status.value shouldBe 400
        error.message shouldBe "delivery_required_field_missing"
    }

    "order delivery upsert updates timestamps".config(enabled = dockerAvailable) {
        Assumptions.assumeTrue(dockerReady, "Docker недоступен или контейнер не стартовал.")
        val cfg = baseTestConfig()
        val merchantId = "m-${UUID.randomUUID()}"
        insertMerchant(merchantId)
        insertDeliveryMethod(merchantId, "[\"pvzCode\",\"pvzAddress\"]")
        val order = createOrder(merchantId, 11L)

        val service = DeliveryService(
            cfg,
            ordersRepository,
            deliveryMethodsRepository,
            orderDeliveryRepository,
            buyerDeliveryProfileRepository
        )

        val first = runBlocking {
            service.setOrderDelivery(
                order.id,
                order.userId,
                JsonObject(
                    mapOf(
                        "pvzCode" to JsonPrimitive("123"),
                        "pvzAddress" to JsonPrimitive("Street 1")
                    )
                )
            )
        }
        Thread.sleep(5)
        val second = runBlocking {
            service.setOrderDelivery(
                order.id,
                order.userId,
                JsonObject(
                    mapOf(
                        "pvzCode" to JsonPrimitive("456"),
                        "pvzAddress" to JsonPrimitive("Street 2")
                    )
                )
            )
        }

        first.createdAt shouldBe second.createdAt
        (second.updatedAt.isAfter(first.updatedAt) || second.updatedAt == first.updatedAt) shouldBe true
    }

    "buyer delivery profile save and get".config(enabled = dockerAvailable) {
        Assumptions.assumeTrue(dockerReady, "Docker недоступен или контейнер не стартовал.")
        val cfg = baseTestConfig()
        val merchantId = cfg.merchants.defaultMerchantId
        insertMerchant(merchantId)
        insertDeliveryMethod(merchantId, "[\"pvzCode\",\"pvzAddress\"]")

        val service = DeliveryService(
            cfg,
            ordersRepository,
            deliveryMethodsRepository,
            orderDeliveryRepository,
            buyerDeliveryProfileRepository
        )

        val fields = JsonObject(
            mapOf(
                "pvzCode" to JsonPrimitive("999"),
                "pvzAddress" to JsonPrimitive("Addr")
            )
        )
        runBlocking { service.setBuyerDeliveryProfile(42L, fields) }
        val fetched = runBlocking { service.getBuyerDeliveryProfile(42L) }
        fetched?.fieldsJson shouldBe DeliveryFieldsCodec.encodeFields(fields)
    }
})
