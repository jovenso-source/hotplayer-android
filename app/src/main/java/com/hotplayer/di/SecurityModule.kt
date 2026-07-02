package com.hotplayer.di

import com.hotplayer.security.AndroidKeystoreManager
import com.hotplayer.security.DeviceIdentityManager
import com.hotplayer.security.DeviceSignatureBuilder
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object SecurityModule {

    @Provides @Singleton
    fun provideAndroidKeystoreManager(): AndroidKeystoreManager = AndroidKeystoreManager()

    @Provides @Singleton
    fun provideDeviceSignatureBuilder(
        keystore: AndroidKeystoreManager
    ): DeviceSignatureBuilder = DeviceSignatureBuilder(keystore)
}
