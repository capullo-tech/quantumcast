package tech.capullo.quantumcast.data.settings

import android.util.Log
import androidx.datastore.core.handlers.ReplaceFileCorruptionHandler
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.preferencesOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import java.io.IOException

private const val TAG = "Settings"

/**
 * Present in the file that replaced a corrupt one, and removed when the user has been told. It is
 * kept in the store itself so that the notice survives a process that ends before a screen shows
 * it: the playback service and the recorder read their stores with no activity on screen.
 */
internal val RESET_NOTICE = booleanPreferencesKey("reset_notice")

/**
 * Replaces a Preferences DataStore file that cannot be parsed with one that holds only
 * [RESET_NOTICE], so that the store reads and writes again and the user learns of the loss.
 *
 * Without a handler DataStore throws the `CorruptionException` again on every read and every
 * write and leaves the file as it is. Seen on a OnePlus 3 (Android 11, 2026-11-27) with 32 bytes
 * of 0xFF in place of each file: with a corrupt `settings` the app started on the defaults with
 * no message and crashed at the first changed setting; with a corrupt `recording`, whose flow had
 * no `catch`, it crashed at every start.
 */
internal fun replaceCorruptStore(name: String) = ReplaceFileCorruptionHandler { e ->
    Log.e(TAG, "$name: the file is corrupt, replaced by the defaults", e)
    preferencesOf(RESET_NOTICE to true)
}

/**
 * Gives the defaults in place of a file that cannot be read, and logs why. Corruption does not
 * arrive here ([replaceCorruptStore] has replaced the file by then); what does is an I/O failure
 * such as a file that cannot be opened. Anything that is not an [IOException] is a defect in the
 * app and is thrown again: the earlier `catch` of everything hid those too.
 *
 * Nothing is lost by this: the file is left alone, and a write reads it again before it changes
 * it. The flow ends at the failure, so its consumer keeps the defaults until the next start, and
 * a write still throws while the file cannot be read (seen with a file of mode 000).
 */
internal fun Flow<Preferences>.defaultsOnReadError(name: String): Flow<Preferences> = catch { e ->
    if (e !is IOException) throw e
    Log.e(TAG, "$name: read failed, using the defaults", e)
    emit(emptyPreferences())
}
