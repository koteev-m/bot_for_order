package com.example.app.di

import com.example.app.config.AppConfig
import com.example.db.DatabaseFactory
import com.example.db.DatabaseTx
import com.example.db.ChannelBindingsRepository
import com.example.db.ChannelBindingsRepositoryExposed
import com.example.db.CartsRepository
import com.example.db.CartsRepositoryExposed
import com.example.db.CartItemsRepository
import com.example.db.CartItemsRepositoryExposed
import com.example.db.AdminUsersRepository
import com.example.db.AdminUsersRepositoryExposed
import com.example.db.ItemMediaRepository
import com.example.db.ItemMediaRepositoryExposed
import com.example.db.ItemsRepository
import com.example.db.ItemsRepositoryExposed
import com.example.db.LinkContextsRepository
import com.example.db.LinkContextsRepositoryExposed
import com.example.db.MerchantsRepository
import com.example.db.MerchantsRepositoryExposed
import com.example.db.OffersRepository
import com.example.db.OffersRepositoryExposed
import com.example.db.OrderAttachmentsRepository
import com.example.db.OrderAttachmentsRepositoryExposed
import com.example.db.OrderLinesRepository
import com.example.db.OrderLinesRepositoryExposed
import com.example.db.OrderPaymentClaimsRepository
import com.example.db.OrderPaymentClaimsRepositoryExposed
import com.example.db.OrderPaymentDetailsRepository
import com.example.db.OrderPaymentDetailsRepositoryExposed
import com.example.db.OrderStatusHistoryRepository
import com.example.db.OrderStatusHistoryRepositoryExposed
import com.example.db.OrdersRepository
import com.example.db.OrdersRepositoryExposed
import com.example.db.PostsRepository
import com.example.db.PostsRepositoryExposed
import com.example.db.PricesDisplayRepository
import com.example.db.PricesDisplayRepositoryExposed
import com.example.db.StorefrontsRepository
import com.example.db.StorefrontsRepositoryExposed
import com.example.db.VariantsRepository
import com.example.db.VariantsRepositoryExposed
import com.example.db.WatchlistRepositoryExposed
import com.example.db.MerchantPaymentMethodsRepository
import com.example.db.MerchantPaymentMethodsRepositoryExposed
import com.example.db.MerchantDeliveryMethodsRepository
import com.example.db.MerchantDeliveryMethodsRepositoryExposed
import com.example.db.OrderDeliveryRepository
import com.example.db.OrderDeliveryRepositoryExposed
import com.example.db.BuyerDeliveryProfileRepository
import com.example.db.BuyerDeliveryProfileRepositoryExposed
import com.example.db.AuditLogRepository
import com.example.db.AuditLogRepositoryExposed
import com.example.db.EventLogRepository
import com.example.db.EventLogRepositoryExposed
import com.example.db.IdempotencyRepository
import com.example.db.IdempotencyRepositoryExposed
import com.example.domain.watchlist.WatchlistRepository
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.koin.dsl.module
import javax.sql.DataSource

fun dbModule(cfg: AppConfig) = module {
    single<DataSource> {
        DatabaseFactory.createHikari(
            url = cfg.db.url,
            user = cfg.db.user,
            password = cfg.db.password
        )
    }
    single<Flyway> {
        Flyway.configure()
            .dataSource(get())
            .locations("classpath:db/migration")
            .baselineOnMigrate(true)
            .load()
    }
    single<Database> { DatabaseFactory.connect(get()) }
    single { DatabaseTx() }

    single<ItemsRepository> { ItemsRepositoryExposed(get()) }
    single<ItemMediaRepository> { ItemMediaRepositoryExposed(get()) }
    single<VariantsRepository> { VariantsRepositoryExposed(get()) }
    single<PricesDisplayRepository> { PricesDisplayRepositoryExposed(get()) }
    single<PostsRepository> { PostsRepositoryExposed(get()) }
    single<MerchantsRepository> { MerchantsRepositoryExposed(get()) }
    single<AdminUsersRepository> { AdminUsersRepositoryExposed(get()) }
    single<StorefrontsRepository> { StorefrontsRepositoryExposed(get()) }
    single<ChannelBindingsRepository> { ChannelBindingsRepositoryExposed(get()) }
    single<LinkContextsRepository> { LinkContextsRepositoryExposed(get()) }
    single<OffersRepository> { OffersRepositoryExposed(get()) }
    single<OrdersRepository> { OrdersRepositoryExposed(get()) }
    single<OrderLinesRepository> { OrderLinesRepositoryExposed(get()) }
    single<OrderStatusHistoryRepository> { OrderStatusHistoryRepositoryExposed(get()) }
    single<MerchantPaymentMethodsRepository> { MerchantPaymentMethodsRepositoryExposed(get()) }
    single<MerchantDeliveryMethodsRepository> { MerchantDeliveryMethodsRepositoryExposed(get()) }
    single<OrderPaymentDetailsRepository> { OrderPaymentDetailsRepositoryExposed(get()) }
    single<OrderPaymentClaimsRepository> { OrderPaymentClaimsRepositoryExposed(get()) }
    single<OrderAttachmentsRepository> { OrderAttachmentsRepositoryExposed(get()) }
    single<OrderDeliveryRepository> { OrderDeliveryRepositoryExposed(get()) }
    single<BuyerDeliveryProfileRepository> { BuyerDeliveryProfileRepositoryExposed(get()) }
    single<WatchlistRepository> { WatchlistRepositoryExposed(get()) }
    single<CartsRepository> { CartsRepositoryExposed(get()) }
    single<CartItemsRepository> { CartItemsRepositoryExposed(get()) }
    single<AuditLogRepository> { AuditLogRepositoryExposed(get()) }
    single<EventLogRepository> { EventLogRepositoryExposed(get()) }
    single<IdempotencyRepository> { IdempotencyRepositoryExposed(get()) }
}
