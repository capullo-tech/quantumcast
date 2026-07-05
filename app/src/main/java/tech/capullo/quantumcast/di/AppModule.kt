package tech.capullo.quantumcast.di

import android.content.Context
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import tech.capullo.quantumcast.data.settings.SettingsRepository
import tech.capullo.source.radiobrowser.data.db.AppDatabase
import tech.capullo.source.radiobrowser.data.repository.RadioRepository
import javax.inject.Singleton

/**
 * Replaces the manual DI that previously lived inside RadioViewModel
 * (`RadioRepository(AppDatabase.getInstance(app))` / `SettingsRepository(app)`).
 */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {

    @Provides
    @Singleton
    fun provideAppDatabase(@ApplicationContext context: Context): AppDatabase = AppDatabase.getInstance(context)

    // Intentionally NOT @Singleton: RadioRepository holds mutable per-session state
    // (currentServerUrl / api swapped by setServerUrl). Keeping it VM-scoped preserves
    // the pre-Hilt lifecycle - a new instance per ViewModel, not one shared app-wide.
    @Provides
    fun provideRadioRepository(db: AppDatabase): RadioRepository = RadioRepository(db)

    @Provides
    @Singleton
    fun provideSettingsRepository(@ApplicationContext context: Context): SettingsRepository = SettingsRepository(context)
}
