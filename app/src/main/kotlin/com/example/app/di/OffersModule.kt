package com.example.app.di

import com.example.app.services.OfferRepositories
import com.example.app.services.OffersService
import org.koin.dsl.module

val offersModule = module {
    single {
        OffersService(
            repositories = OfferRepositories(
                items = get(),
                variants = get(),
                prices = get(),
                offers = get()
            ),
            holdService = get(),
            lockManager = get(),
            redisson = get()
        )
    }
}
