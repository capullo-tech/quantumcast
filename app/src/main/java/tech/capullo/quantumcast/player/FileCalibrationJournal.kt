package tech.capullo.quantumcast.player

import android.content.Context
import android.util.Log
import tech.capullo.audio.calibration.CalibrationJournal
import java.io.File

/**
 * File-backed [CalibrationJournal] in the app's private storage. One `id=latency` line per
 * client; survives process death and reboots, so [tech.capullo.audio.calibration.SyncCalibrator.recover]
 * can undo a run the OS killed mid-flight. Failures are swallowed and logged — journaling
 * must never crash a calibration.
 */
class FileCalibrationJournal(context: Context) : CalibrationJournal {

    private val file = File(context.filesDir, "calibration_journal")

    override fun save(originals: Map<String, Int>) {
        try {
            file.writeText(originals.entries.joinToString("\n") { "${it.key}=${it.value}" })
        } catch (e: Exception) {
            Log.e(TAG, "save failed: ${e.message}")
        }
    }

    override fun load(): Map<String, Int>? {
        if (!file.exists()) return null
        return try {
            file.readLines()
                .filter { it.isNotBlank() }
                .associate {
                    // ids never contain '='; split on the last one to be safe regardless.
                    val i = it.lastIndexOf('=')
                    it.substring(0, i) to it.substring(i + 1).trim().toInt()
                }
                .takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            Log.e(TAG, "load failed, discarding journal: ${e.message}")
            clear()
            null
        }
    }

    override fun clear() {
        try {
            file.delete()
        } catch (_: Exception) {
        }
    }

    companion object {
        private const val TAG = "CalibrationJournal"
    }
}
