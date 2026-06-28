package com.idp.universalremote.di

import com.idp.universalremote.analytics.Analytics
import com.idp.universalremote.analytics.CrashReporter
import com.idp.universalremote.analytics.NoopAnalytics
import com.idp.universalremote.analytics.NoopCrashReporter
import com.idp.universalremote.premium.AdsManager
import com.idp.universalremote.premium.BillingManager
import com.idp.universalremote.premium.NoopAdsManager
import com.idp.universalremote.premium.NoopBillingManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object ServicesModule {

    @Provides @Singleton
    fun provideAnalytics(): Analytics = NoopAnalytics

    @Provides @Singleton
    fun provideCrashReporter(): CrashReporter = NoopCrashReporter

    @Provides @Singleton
    fun provideAdsManager(): AdsManager = NoopAdsManager

    @Provides @Singleton
    fun provideBillingManager(): BillingManager = NoopBillingManager
}
