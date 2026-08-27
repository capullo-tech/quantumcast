package tech.capullo.quantumcast.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import tech.capullo.audio.tunnel.TunnelManager
import tech.capullo.quantumcast.data.settings.SettingsRepository
import tech.capullo.source.radiobrowser.data.repository.RadioRepository
import javax.inject.Singleton

/**
 * Replaces the manual DI that previously lived inside RadioViewModel
 * (`RadioRepository(context)` / `SettingsRepository(app)`).
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    // Intentionally NOT @Singleton: RadioRepository holds mutable per-session state
    // (currentServerUrl / api swapped by setServerUrl). Keeping it VM-scoped preserves
    // the pre-Hilt lifecycle - a new instance per ViewModel, not one shared app-wide.
    // Built from Context via the library's convenience constructor so QC's DI never names
    // AppDatabase / Room (Room is an implementation detail of capullo-source-radiobrowser).
    @Provides
    fun provideRadioRepository(@ApplicationContext context: Context): RadioRepository = RadioRepository(context)

    @Provides
    @Singleton
    fun provideSettingsRepository(@ApplicationContext context: Context): SettingsRepository = SettingsRepository(context)

    // The library ships TunnelManager without DI annotations so it forces no framework on a
    // consumer; @Singleton here is what keeps it to one instance - and so to one cloudflared
    // process - now that Hilt no longer reads that off the class.
    @Provides
    @Singleton
    fun provideTunnelManager(@ApplicationContext context: Context): TunnelManager = TunnelManager(context)
}
