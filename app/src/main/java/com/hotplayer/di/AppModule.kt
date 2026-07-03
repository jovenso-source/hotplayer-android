package com.hotplayer.di

import android.content.Context
import com.hotplayer.data.api.HotPlayerApi
import com.hotplayer.data.repository.DeviceRepository
import com.hotplayer.data.repository.SessionRepository
import com.hotplayer.security.DeviceIdentityManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // DeviceRepository is auto-injected by Hilt via @Inject constructor — no explicit @Provides needed.

    @Provides @Singleton
    fun provideSessionRepository(
        @ApplicationContext context: Context,
        api: HotPlayerApi,
        deviceRepo: DeviceRepository,
        identity: DeviceIdentityManager
    ): SessionRepository = SessionRepository(context, api, deviceRepo, identity)
}
