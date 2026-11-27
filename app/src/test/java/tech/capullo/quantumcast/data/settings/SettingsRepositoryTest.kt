package tech.capullo.quantumcast.data.settings

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File

class SettingsRepositoryTest {

    @get:Rule
    val folder = TemporaryFolder()

    private val file: File get() = File(folder.root, "settings.preferences_pb")

    /**
     * One process lifetime: a repository over [file], with the corruption handler of the app's
     * own store, released when [block] returns.
     */
    private fun <T> process(block: suspend (SettingsRepository) -> T): T = runBlocking {
        val job = Job()
        val scope = CoroutineScope(Dispatchers.IO + job)
        val store = PreferenceDataStoreFactory.create(
            corruptionHandler = replaceCorruptStore("settings"),
            scope = scope,
        ) { file }
        try {
            block(SettingsRepository(store, scope))
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

    @Test
    fun `a corrupt file is replaced and the notice stays until it is acknowledged`() {
        file.writeBytes(ByteArray(32) { 0xFF.toByte() })

        assertEquals(AppSettings(resetNotice = true), process { it.settings.value })

        // Without the replacement this write throws the CorruptionException of the read.
        process { it.setCustomServerName("Den") }
        // Not acknowledged: a process that ended before a screen showed the notice still owes it.
        assertEquals(
            AppSettings(customServerName = "Den", resetNotice = true),
            process { it.settings.value },
        )

        process { it.acknowledgeReset() }
        assertEquals(AppSettings(customServerName = "Den"), process { it.settings.value })
    }

    @Test
    fun `a file that cannot be read gives the defaults and is left as it is`() {
        // A directory in the file's place fails to open with an IOException that is not corruption.
        file.mkdir()

        assertEquals(AppSettings(), process { it.settings.value })
        assertTrue(file.isDirectory)
    }

    @Test
    fun `a write to a file that cannot be read is lost, not thrown`() {
        file.mkdir()

        val after = process { repository ->
            // Before, this threw the IOException of the read and ended the process.
            repository.setCustomServerName("Den")
            repository.settings.value
        }
        assertEquals(AppSettings(), after)
        assertTrue(file.isDirectory)
    }

    @Test
    fun `a failure that is not an IOException is not turned into the defaults`() {
        val defect = object : DataStore<Preferences> {
            override val data: Flow<Preferences> = flow { throw IllegalStateException("defect") }
            override suspend fun updateData(
                transform: suspend (t: Preferences) -> Preferences,
            ): Preferences = error("not used")
        }

        assertThrows(IllegalStateException::class.java) { SettingsRepository(defect) }
    }
}
