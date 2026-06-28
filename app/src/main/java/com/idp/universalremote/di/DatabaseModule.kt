package com.idp.universalremote.di

import android.content.Context
import androidx.room.Room
import com.idp.universalremote.data.local.AppDatabase
import com.idp.universalremote.data.local.dao.ConnectionHistoryDao
import com.idp.universalremote.data.local.dao.FavoriteRemoteDao
import com.idp.universalremote.data.local.dao.TvDeviceDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DatabaseModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): AppDatabase =
        Room.databaseBuilder(context, AppDatabase::class.java, AppDatabase.DATABASE_NAME)
            .fallbackToDestructiveMigration()
            .build()

    @Provides fun provideTvDeviceDao(db: AppDatabase): TvDeviceDao = db.tvDeviceDao()
    @Provides fun provideFavoriteRemoteDao(db: AppDatabase): FavoriteRemoteDao = db.favoriteRemoteDao()
    @Provides fun provideConnectionHistoryDao(db: AppDatabase): ConnectionHistoryDao = db.connectionHistoryDao()
}
