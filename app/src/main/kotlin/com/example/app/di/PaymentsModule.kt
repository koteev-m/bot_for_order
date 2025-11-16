package com.example.app.di

import com.example.app.services.PaymentsService
import com.example.app.services.PaymentsServiceImpl
import org.koin.dsl.module

val paymentsModule = module {
    single<PaymentsService> { PaymentsServiceImpl(get(), get(), get(), get()) }
}
