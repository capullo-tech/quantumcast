package tech.capullo.quantumcast.data.settings

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.*
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.runBlocking

private const val SETTINGS_STORE = "settings"

val Context.dataStore: DataStore<Preferences> by preferencesDataStore(
    name = SETTINGS_STORE,
    corruptionHandler = replaceCorruptStore(SETTINGS_STORE),
)

enum class ThemeMode { SYSTEM, LIGHT, DARK }

enum class ShareService { YOUTUBE, SPOTIFY, APPLE_MUSIC }

enum class BroadcastMode {
    QUANTUMCAST, // VLC → FIFO → Snapserver + local Snapclient (always on)
    SNAPCLIENT, // Qcast tab: Snapclient connected to external Snapserver
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
    // Stereo balance applied to the broadcast mix: -1 = full left, 0 = center, +1 = full right.
    val balance: Float = 0f,
    // Live rolling loudness normalization (LiveLoudnessAgcProcessor) for the broadcast mix -
    // continuously slews gain toward -18 LUFS over tens of seconds, correcting for one station
    // being much louder/quieter than another. Default on; most streams are already loudness-
    // normalized upstream by their own encoder, but this corrects the cases that aren't.
    val normalizationEnabled: Boolean = true,
    // This device's own snapclient spatial role - persisted so a set-once L/R + latency + volume
    // arrangement survives a restart ("power on and it's working"). Defaults = today's behaviour.
    val snapclientChannel: String = "stereo",
    val snapclientVolume: Int = 100,
    val snapclientLatency: Int = 0,
    // Snapserver base port: 0 = random base (default). >0 pins the trio to
    // base/base+1/base+2 so the server address survives broadcast restarts.
    val snapserverFixedPort: Int = 0,
    // The settings file was corrupt and has been replaced by the defaults; true until the user
    // has been told (see replaceCorruptStore).
    val resetNotice: Boolean = false,
)

enum class RadioServer(val label: String, val url: String) {
    DE1("Germany (de1)", "https://de1.api.radio-browser.info/"),
    NL1("Netherlands (nl1)", "https://nl1.api.radio-browser.info/"),
    AT1("Austria (at1)", "https://at1.api.radio-browser.info/"),
    CUSTOM("Custom", ""),
}

/**
 * The app's settings, kept in a Preferences DataStore.
 *
 * [settings] never holds the defaults of [AppSettings] in place of what the user saved: the
 * constructor blocks until the file is read. Before this, each consumer started from the defaults
 * and learned the saved values some milliseconds later, and three of them needed their own
 * workaround: launch-time auto-entangle read `false`, the service advertised the device model
 * until the saved server name arrived, and the saved spatial role had to win a race against the
 * first client connection. The theme was the visible case: with a forced theme that differs
 * from the system's, a OnePlus 3 drew the whole first screen in the wrong theme for about 0.5 s.
 *
 * The cost is one small file read on the thread that creates the repository, once per process:
 * 3 ms on a razr 40 Ultra and 18 to 27 ms on a OnePlus 3 (2026-11-27, logged by AppModule). Cold
 * start time on the OnePlus 3 did not change: 1304 to 1325 ms before, 1300 to 1349 ms after,
 * five starts each. If the settings file ever holds large values, measure again.
 */
class SettingsRepository(
    private val store: DataStore<Preferences>,
    scope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
) {

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
        val BALANCE = floatPreferencesKey("balance")
        val NORMALIZATION_ENABLED = booleanPreferencesKey("normalization_enabled")
        val SNAPCLIENT_CHANNEL = stringPreferencesKey("snapclient_channel")
        val SNAPCLIENT_VOLUME = intPreferencesKey("snapclient_volume")
        val SNAPCLIENT_LATENCY = intPreferencesKey("snapclient_latency")
        val SNAPSERVER_FIXED_PORT = intPreferencesKey("snapserver_fixed_port")
    }

    private val stored: Flow<AppSettings> = store.data
        .defaultsOnReadError(SETTINGS_STORE)
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
                balance = prefs[Keys.BALANCE] ?: 0f,
                normalizationEnabled = prefs[Keys.NORMALIZATION_ENABLED] ?: true,
                snapclientChannel = prefs[Keys.SNAPCLIENT_CHANNEL] ?: "stereo",
                snapclientVolume = prefs[Keys.SNAPCLIENT_VOLUME] ?: 100,
                snapclientLatency = prefs[Keys.SNAPCLIENT_LATENCY] ?: 0,
                snapserverFixedPort = prefs[Keys.SNAPSERVER_FIXED_PORT] ?: 0,
                resetNotice = prefs[RESET_NOTICE] ?: false,
            )
        }

    val settings: StateFlow<AppSettings> =
        stored.stateIn(scope, SharingStarted.Eagerly, runBlocking { stored.first() })

    suspend fun update(block: suspend (MutablePreferences) -> Unit) {
        store.editOrLog(SETTINGS_STORE, block)
    }

    /** The user has been told that the settings were reset. */
    suspend fun acknowledgeReset() = update { it.remove(RESET_NOTICE) }

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
    suspend fun setBalance(v: Float) = update { it[Keys.BALANCE] = v.coerceIn(-1f, 1f) }
    suspend fun setNormalizationEnabled(v: Boolean) = update { it[Keys.NORMALIZATION_ENABLED] = v }
    suspend fun setSnapclientChannel(v: String) = update { it[Keys.SNAPCLIENT_CHANNEL] = v }
    suspend fun setSnapclientVolume(v: Int) = update { it[Keys.SNAPCLIENT_VOLUME] = v.coerceIn(0, 100) }
    suspend fun setSnapclientLatency(v: Int) = update { it[Keys.SNAPCLIENT_LATENCY] = v }

    /** 0 = random base; otherwise the base port (clamped to a safe range). */
    suspend fun setSnapserverFixedPort(v: Int) = update {
        it[Keys.SNAPSERVER_FIXED_PORT] = normalizeSnapserverFixedPort(v)
    }
}

/** Stored snapserver base port: <= 0 means random (0); otherwise clamped to 1024..65531. */
internal fun normalizeSnapserverFixedPort(v: Int): Int = if (v <= 0) 0 else v.coerceIn(1024, 65531)
