package com.idp.universalremote.di

import com.idp.universalremote.data.discovery.NsdDeviceDiscovery
import com.idp.universalremote.data.repository.RemoteCommandRepositoryImpl
import com.idp.universalremote.data.repository.TvDeviceRepositoryImpl
import com.idp.universalremote.domain.repository.DeviceDiscoveryRepository
import com.idp.universalremote.domain.repository.RemoteCommandRepository
import com.idp.universalremote.domain.repository.TvDeviceRepository
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class RepositoryModule {

    @Binds
    @Singleton
    abstract fun bindTvDeviceRepository(impl: TvDeviceRepositoryImpl): TvDeviceRepository

    @Binds
    @Singleton
    abstract fun bindDiscoveryRepository(impl: NsdDeviceDiscovery): DeviceDiscoveryRepository

    @Binds
    @Singleton
    abstract fun bindRemoteCommandRepository(impl: RemoteCommandRepositoryImpl): RemoteCommandRepository
}
