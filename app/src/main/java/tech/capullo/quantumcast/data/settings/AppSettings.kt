package tech.capullo.quantumcast.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "settings")

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class ShareService { YOUTUBE, SPOTIFY, APPLE_MUSIC }

enum class BroadcastMode {
    QUANTUMCAST,   // VLC → FIFO → Snapserver + local Snapclient (always on)
    SNAPCLIENT     // Qcast tab: Snapclient connected to external Snapserver
}

data class AppSettings(
    val apiServer: String = RadioServer.DE1.url,
    val searchLimit: Int = 40,
    val randomBatchSize: Int = 10,
    val rotationMinutes: Int = 5,
    val shazamIntervalSeconds: Int = 120,
    val sleepTimerMinutes: Int = 30,
    val themeMode: ThemeMode = ThemeMode.SYSTEM,
    val maxHistorySongs: Int = 0,
    val vlcNetworkCachingMs: Int = 3000,
    val lastManualHost: String = "",
    val shareService: ShareService = ShareService.YOUTUBE,
    val customServerName: String = "",
    val autoEntangleOnLaunch: Boolean = false,
    val webDebugPanel: Boolean = false,
    val webAutoplay: Boolean = false,
)

enum class RadioServer(val label: String, val url: String) {
    DE1("Germany (de1)", "https://de1.api.radio-browser.info/"),
    NL1("Netherlands (nl1)", "https://nl1.api.radio-browser.info/"),
    AT1("Austria (at1)", "https://at1.api.radio-browser.info/"),
    CUSTOM("Custom", "")
}

class SettingsRepository(private val context: Context) {

    private object Keys {
        val API_SERVER = stringPreferencesKey("api_server")
        val SEARCH_LIMIT = intPreferencesKey("search_limit")
        val RANDOM_BATCH_SIZE = intPreferencesKey("random_batch_size")
        val ROTATION_MINUTES = intPreferencesKey("random_rotation_minutes")
        val SHAZAM_INTERVAL_SECONDS = intPreferencesKey("shazam_interval_seconds")
        val SLEEP_TIMER_MINUTES = intPreferencesKey("sleep_timer_minutes")
        val THEME_MODE = stringPreferencesKey("theme_mode")
        val MAX_HISTORY_SONGS = intPreferencesKey("max_history_songs")
        val VLC_NETWORK_CACHING_MS = intPreferencesKey("vlc_network_caching_ms")
        val LAST_MANUAL_HOST = stringPreferencesKey("last_manual_host")
        val SHARE_SERVICE = stringPreferencesKey("share_service")
        val CUSTOM_SERVER_NAME = stringPreferencesKey("custom_server_name")
        val AUTO_ENTANGLE_ON_LAUNCH = booleanPreferencesKey("auto_entangle_on_launch")
        val WEB_DEBUG_PANEL = booleanPreferencesKey("web_debug_panel")
        val WEB_AUTOPLAY = booleanPreferencesKey("web_autoplay")
    }

    val settings: Flow<AppSettings> = context.dataStore.data
        .catch { emit(emptyPreferences()) }
        .map { prefs ->
            AppSettings(
                apiServer = prefs[Keys.API_SERVER] ?: AppSettings().apiServer,
                searchLimit = prefs[Keys.SEARCH_LIMIT] ?: AppSettings().searchLimit,
                randomBatchSize = prefs[Keys.RANDOM_BATCH_SIZE] ?: AppSettings().randomBatchSize,
                rotationMinutes = prefs[Keys.ROTATION_MINUTES] ?: AppSettings().rotationMinutes,
                shazamIntervalSeconds = prefs[Keys.SHAZAM_INTERVAL_SECONDS] ?: AppSettings().shazamIntervalSeconds,
                sleepTimerMinutes = prefs[Keys.SLEEP_TIMER_MINUTES] ?: AppSettings().sleepTimerMinutes,
                themeMode = prefs[Keys.THEME_MODE]
                    ?.let { runCatching { ThemeMode.valueOf(it) }.getOrNull() }
                    ?: ThemeMode.SYSTEM,
                maxHistorySongs = prefs[Keys.MAX_HISTORY_SONGS] ?: 0,
                vlcNetworkCachingMs = prefs[Keys.VLC_NETWORK_CACHING_MS] ?: AppSettings().vlcNetworkCachingMs,
                lastManualHost = prefs[Keys.LAST_MANUAL_HOST] ?: "",
                shareService = prefs[Keys.SHARE_SERVICE]
                    ?.let { runCatching { ShareService.valueOf(it) }.getOrNull() }
                    ?: ShareService.YOUTUBE,
                customServerName = prefs[Keys.CUSTOM_SERVER_NAME] ?: "",
                autoEntangleOnLaunch = prefs[Keys.AUTO_ENTANGLE_ON_LAUNCH] ?: false,
                webDebugPanel = prefs[Keys.WEB_DEBUG_PANEL] ?: false,
                webAutoplay = prefs[Keys.WEB_AUTOPLAY] ?: false,
            )
        }

    suspend fun update(block: suspend (MutablePreferences) -> Unit) {
        context.dataStore.edit { block(it) }
    }

    suspend fun setApiServer(url: String) = update { it[Keys.API_SERVER] = url }
    suspend fun setSearchLimit(v: Int) = update { it[Keys.SEARCH_LIMIT] = v }
    suspend fun setRandomBatchSize(v: Int) = update { it[Keys.RANDOM_BATCH_SIZE] = v }
    suspend fun setRotationMinutes(v: Int) = update { it[Keys.ROTATION_MINUTES] = v }
    suspend fun setShazamIntervalSeconds(v: Int) = update { it[Keys.SHAZAM_INTERVAL_SECONDS] = v }
    suspend fun setSleepTimerMinutes(v: Int) = update { it[Keys.SLEEP_TIMER_MINUTES] = v }
    suspend fun setThemeMode(v: ThemeMode) = update { it[Keys.THEME_MODE] = v.name }
    suspend fun setMaxHistorySongs(v: Int) = update { it[Keys.MAX_HISTORY_SONGS] = v.coerceAtLeast(0) }
    suspend fun setVlcNetworkCachingMs(v: Int) = update { it[Keys.VLC_NETWORK_CACHING_MS] = v.coerceIn(100, 10000) }
    suspend fun setLastManualHost(v: String) = update { it[Keys.LAST_MANUAL_HOST] = v }
    suspend fun setShareService(v: ShareService) = update { it[Keys.SHARE_SERVICE] = v.name }
    suspend fun setCustomServerName(v: String) = update { it[Keys.CUSTOM_SERVER_NAME] = v.trim() }
    suspend fun setAutoEntangleOnLaunch(v: Boolean) = update { it[Keys.AUTO_ENTANGLE_ON_LAUNCH] = v }
    suspend fun setWebDebugPanel(v: Boolean) = update { it[Keys.WEB_DEBUG_PANEL] = v }
    suspend fun setWebAutoplay(v: Boolean) = update { it[Keys.WEB_AUTOPLAY] = v }
}
