package com.example.app.db

import com.example.db.ChannelBindingsRepositoryExposed
import com.example.db.DatabaseFactory
import com.example.db.DatabaseTx
import com.example.db.ItemsRepositoryExposed
import com.example.db.LinkContextsRepositoryExposed
import com.example.db.StorefrontsRepositoryExposed
import com.example.domain.Item
import com.example.domain.ItemStatus
import com.example.domain.LinkAction
import com.example.domain.LinkButton
import com.example.domain.LinkContext
import com.example.domain.Storefront
import com.zaxxer.hikari.HikariDataSource
import io.kotest.core.spec.style.StringSpec
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import java.time.Instant
import java.util.UUID
import org.flywaydb.core.Flyway
import org.testcontainers.containers.PostgreSQLContainer

class MerchantLinkContextRepositoryTest : StringSpec({
    val postgres = PostgreSQLContainer<Nothing>("postgres:16-alpine")
    lateinit var dataSource: HikariDataSource
    lateinit var dbTx: DatabaseTx
    lateinit var itemsRepository: ItemsRepositoryExposed
    lateinit var storefrontsRepository: StorefrontsRepositoryExposed
    lateinit var channelBindingsRepository: ChannelBindingsRepositoryExposed
    lateinit var linkContextsRepository: LinkContextsRepositoryExposed

    beforeSpec {
        postgres.start()
        dataSource = DatabaseFactory.createHikari(
            url = postgres.jdbcUrl,
            user = postgres.username,
            password = postgres.password
        )
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        DatabaseFactory.connect(dataSource)
        dbTx = DatabaseTx()
        itemsRepository = ItemsRepositoryExposed(dbTx)
        storefrontsRepository = StorefrontsRepositoryExposed(dbTx)
        channelBindingsRepository = ChannelBindingsRepositoryExposed(dbTx)
        linkContextsRepository = LinkContextsRepositoryExposed(dbTx)
    }

    afterSpec {
        dataSource.close()
        postgres.stop()
    }

    "migrations create default merchant and backfill items" {
        val merchantId = dataSource.connection.use { conn ->
            conn.prepareStatement("SELECT id FROM merchants WHERE id = 'default'").use { stmt ->
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getString("id")
                }
            }
        }
        merchantId shouldBe "default"

        val itemId = "item_${UUID.randomUUID()}"
        val now = Instant.now()
        dataSource.connection.use { conn ->
            conn.prepareStatement(
                """
                    INSERT INTO items (
                        id,
                        title,
                        description,
                        status,
                        allow_bargain,
                        created_at,
                        updated_at
                    ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """.trimIndent()
            ).use { stmt ->
                stmt.setString(1, itemId)
                stmt.setString(2, "Title")
                stmt.setString(3, "Description")
                stmt.setString(4, ItemStatus.active.name)
                stmt.setBoolean(5, false)
                stmt.setObject(6, now)
                stmt.setObject(7, now)
                stmt.executeUpdate()
            }
            conn.prepareStatement("SELECT merchant_id FROM items WHERE id = ?").use { stmt ->
                stmt.setString(1, itemId)
                stmt.executeQuery().use { rs ->
                    rs.next()
                    rs.getString("merchant_id") shouldBe "default"
                }
            }
        }
    }

    "creates channel binding and link context, then revokes by token hash" {
        val merchantId = "default"
        val storefront = Storefront(
            id = UUID.randomUUID().toString(),
            merchantId = merchantId,
            name = "Main"
        )
        storefrontsRepository.create(storefront)
        val channelId = 123456789L
        val bindingId = channelBindingsRepository.bind(storefront.id, channelId, Instant.now())
        bindingId shouldNotBe 0L

        val item = Item(
            id = UUID.randomUUID().toString(),
            merchantId = merchantId,
            title = "Title",
            description = "Desc",
            status = ItemStatus.active,
            allowBargain = false,
            bargainRules = null
        )
        itemsRepository.create(item)

        val tokenHash = "hash_${UUID.randomUUID()}"
        val createdAt = Instant.now()
        val context = LinkContext(
            id = 0,
            tokenHash = tokenHash,
            merchantId = merchantId,
            storefrontId = storefront.id,
            channelId = channelId,
            postMessageId = null,
            listingId = item.id,
            action = LinkAction.ADD,
            button = LinkButton.BUY,
            createdAt = createdAt,
            revokedAt = null,
            expiresAt = createdAt.plusSeconds(3600),
            metadataJson = """{"source":"test"}"""
        )
        val contextId = linkContextsRepository.create(context)
        contextId shouldNotBe 0L

        val stored = linkContextsRepository.getByTokenHash(tokenHash)
        stored shouldNotBe null
        stored?.revokedAt shouldBe null

        linkContextsRepository.revokeByTokenHash(tokenHash, Instant.now()) shouldBe true
        val revoked = linkContextsRepository.getByTokenHash(tokenHash)
        revoked?.revokedAt shouldNotBe null
    }
})
