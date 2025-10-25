package com.example.app.di

import com.example.app.services.ItemsService
import com.example.app.services.MediaStateStore
import com.example.app.services.PostService
import com.example.db.ItemMediaRepository
import com.example.db.ItemsRepository
import com.example.db.PostsRepository
import org.koin.dsl.module

val adminModule = module {
    single { ItemsService(get<ItemsRepository>()) }
    single { MediaStateStore() }
    single { PostService(get(), get(), get<ItemsRepository>(), get<ItemMediaRepository>(), get<PostsRepository>()) }
}
