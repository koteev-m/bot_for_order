package com.example.app.di

import com.example.app.config.AppConfig
import com.example.app.services.ItemsService
import com.example.app.services.MediaStateStore
import com.example.app.services.OrderStatusService
import com.example.app.services.PostService
import com.example.db.ChannelBindingsRepository
import com.example.db.ItemMediaRepository
import com.example.db.ItemsRepository
import com.example.db.PostsRepository
import com.example.db.StorefrontsRepository
import org.koin.dsl.module

val adminModule = module {
    single { ItemsService(get<ItemsRepository>(), get<AppConfig>().merchants) }
    single { MediaStateStore() }
    single {
        PostService(
            get(),
            get(),
            get<ItemsRepository>(),
            get<ItemMediaRepository>(),
            get<PostsRepository>(),
            get(),
            get<ChannelBindingsRepository>(),
            get<StorefrontsRepository>()
        )
    }
    single { OrderStatusService(get(), get(), get(), get(), get()) }
}
