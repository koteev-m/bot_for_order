package com.example.app.di

import com.example.app.services.ItemsService
import com.example.app.services.MediaStateStore
import com.example.db.ItemsRepository
import org.koin.dsl.module

val adminModule = module {
    single { ItemsService(get<ItemsRepository>()) }
    single { MediaStateStore() }
}
