package com.example.app.di

import com.example.app.config.AppConfig
import com.example.db.DatabaseFactory
import com.example.db.DatabaseTx
import com.example.db.ItemMediaRepository
import com.example.db.ItemMediaRepositoryExposed
import com.example.db.ItemsRepository
import com.example.db.ItemsRepositoryExposed
import com.example.db.OffersRepository
import com.example.db.OffersRepositoryExposed
import com.example.db.OrderStatusHistoryRepository
import com.example.db.OrderStatusHistoryRepositoryExposed
import com.example.db.OrdersRepository
import com.example.db.OrdersRepositoryExposed
import com.example.db.PostsRepository
import com.example.db.PostsRepositoryExposed
import com.example.db.PricesDisplayRepository
import com.example.db.PricesDisplayRepositoryExposed
import com.example.db.VariantsRepository
import com.example.db.VariantsRepositoryExposed
import com.example.db.WatchlistRepository
import com.example.db.WatchlistRepositoryExposed
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
    single<OffersRepository> { OffersRepositoryExposed(get()) }
    single<OrdersRepository> { OrdersRepositoryExposed(get()) }
    single<OrderStatusHistoryRepository> { OrderStatusHistoryRepositoryExposed(get()) }
    single<WatchlistRepository> { WatchlistRepositoryExposed(get()) }
}
