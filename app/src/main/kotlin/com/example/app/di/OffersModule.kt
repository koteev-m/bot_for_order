package com.example.app.di

import com.example.app.services.OfferRepositories
import com.example.app.services.OfferServicesDeps
import com.example.app.services.OffersService
import org.koin.dsl.module

val offersModule = module {
    single {
        OffersService(
            repositories = OfferRepositories(
                items = get(),
                variants = get(),
                prices = get(),
                offers = get(),
                orders = get()
            ),
            deps = OfferServicesDeps(
                holdService = get(),
                lockManager = get(),
                redisson = get(),
                paymentsService = get(),
                clients = get(),
                config = get()
            )
        )
    }
}
