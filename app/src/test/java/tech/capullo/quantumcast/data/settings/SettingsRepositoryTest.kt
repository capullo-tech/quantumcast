package tech.capullo.quantumcast.data.settings

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SettingsRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val file: File get() = File(folder.root, "settings.preferences_pb")

    /** One process lifetime: a repository over [file], released when [block] returns. */
    private fun <T> process(block: suspend (SettingsRepository) -> T): T = runBlocking {
        val job = Job()
        val scope = CoroutineScope(Dispatchers.IO + job)
        try {
            block(SettingsRepository(PreferenceDataStoreFactory.create(scope = scope) { file }, scope))
        } finally {
            // DataStore allows one instance per file; the file is free once its scope has ended.
            job.cancelAndJoin()
        }
    }

    @Test
    fun `a fresh install starts from the defaults`() {
        assertEquals(AppSettings(), process { it.settings.value })
    }

    @Test
    fun `the value at start is what an earlier process saved`() {
        process {
            it.setCustomServerName("Kitchen")
            it.setAutoEntangleOnLaunch(true)
            it.setSnapclientChannel("left")
            it.setSnapclientLatency(120)
            it.setSnapserverFixedPort(41000)
            it.setThemeMode(ThemeMode.DARK)
        }

        // No wait and no collector: the first thing a consumer reads must already be the saved state.
        val atStart = process { it.settings.value }

        assertEquals(
            AppSettings(
                customServerName = "Kitchen",
                autoEntangleOnLaunch = true,
                snapclientChannel = "left",
                snapclientLatency = 120,
                snapserverFixedPort = 41000,
                themeMode = ThemeMode.DARK,
            ),
            atStart,
        )
    }

    @Test
    fun `a write reaches the value without a restart`() {
        val name = process { repository ->
            repository.setCustomServerName("  Den ")
            withTimeout(5_000) { repository.settings.first { it.customServerName.isNotEmpty() } }.customServerName
        }
        assertEquals("Den", name)
    }
}
