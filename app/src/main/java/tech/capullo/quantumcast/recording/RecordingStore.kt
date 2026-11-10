package tech.capullo.quantumcast.recording

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.Log
import androidx.core.content.FileProvider
import java.io.File
import java.io.OutputStream
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * The folder recordings go to: the app's own folder by default, or a user-picked SAF tree.
 * Files are written raw (exactly what the station sends), so a file is valid up to its last
 * write - there is nothing to finalize, and a killed process leaves a playable recording.
 *
 * Rotation only ever touches files this app created (names in the index), so a user-picked
 * folder's other contents are safe, and never a file still being written.
 */
class RecordingStore(private val context: Context, private val treeUri: Uri?) {

    val isInternal: Boolean get() = treeUri == null

    fun internalDir(): File = File(context.getExternalFilesDir(null) ?: context.filesDir, "Recordings")
        .apply { mkdirs() }

    /** Every file in the folder that the index says is ours, newest first. */
    fun list(index: Map<String, RecMeta>, active: Set<String>): List<RecordingFile> {
        val all = if (treeUri == null) listInternal(index, active) else listTree(index, active)
        return all.sortedByDescending { it.lastModified }
    }

    private fun listInternal(index: Map<String, RecMeta>, active: Set<String>): List<RecordingFile> = internalDir().listFiles().orEmpty()
        .filter { it.isFile && it.name in index }
        .map { f ->
            RecordingFile(
                name = f.name,
                uri = FileProvider.getUriForFile(context, "${context.packageName}.recordings", f),
                size = f.length(),
                lastModified = f.lastModified(),
                meta = index[f.name] ?: RecMeta(),
                inProgress = f.name in active,
            )
        }

    private fun listTree(index: Map<String, RecMeta>, active: Set<String>): List<RecordingFile> {
        val tree = treeUri ?: return emptyList()
        val children = DocumentsContract.buildChildDocumentsUriUsingTree(tree, DocumentsContract.getTreeDocumentId(tree))
        val cols = arrayOf(
            DocumentsContract.Document.COLUMN_DOCUMENT_ID,
            DocumentsContract.Document.COLUMN_DISPLAY_NAME,
            DocumentsContract.Document.COLUMN_SIZE,
            DocumentsContract.Document.COLUMN_LAST_MODIFIED,
        )
        val out = mutableListOf<RecordingFile>()
        runCatching {
            context.contentResolver.query(children, cols, null, null, null)?.use { c ->
                while (c.moveToNext()) {
                    val name = c.getString(1) ?: continue
                    if (name !in index) continue
                    out += RecordingFile(
                        name = name,
                        uri = DocumentsContract.buildDocumentUriUsingTree(tree, c.getString(0)),
                        size = c.getLong(2),
                        lastModified = c.getLong(3),
                        meta = index[name] ?: RecMeta(),
                        inProgress = name in active,
                    )
                }
            }
        }.onFailure { Log.w(TAG, "list folder failed (permission revoked?)", it) }
        return out
    }

    /** Creates a new file and opens it for writing. Returns the final display name and stream. */
    fun create(station: String, ext: String, mime: String, startedAt: LocalDateTime): Pair<String, OutputStream> {
        val base = "${sanitize(station)}_${startedAt.format(STAMP)}"
        return if (treeUri == null) {
            val dir = internalDir()
            var f = File(dir, "$base.$ext")
            var n = 2
            while (f.exists()) f = File(dir, "${base}_${n++}.$ext")
            f.name to f.outputStream()
        } else {
            val parent = DocumentsContract.buildDocumentUriUsingTree(treeUri, DocumentsContract.getTreeDocumentId(treeUri))
            val doc = DocumentsContract.createDocument(context.contentResolver, parent, mime, "$base.$ext")
                ?: error("Cannot create a file in the chosen folder")
            // Providers may de-duplicate the name ("x (1).mp3"); the index must hold the real one.
            val name = context.contentResolver.query(doc, arrayOf(DocumentsContract.Document.COLUMN_DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null } ?: "$base.$ext"
            val os = context.contentResolver.openOutputStream(doc, "w") ?: error("Cannot open the new file")
            name to os
        }
    }

    fun delete(file: RecordingFile): Boolean = runCatching {
        if (treeUri == null) {
            File(internalDir(), file.name).delete()
        } else {
            DocumentsContract.deleteDocument(context.contentResolver, file.uri)
        }
    }.getOrDefault(false)

    /**
     * Deletes the oldest finished recordings until the folder's recordings fit in [capBytes].
     * Returns the names deleted. capBytes <= 0 means no cap. A recording still being written is
     * never deleted, so the folder can sit above the cap while one is in progress.
     */
    fun enforceCap(capBytes: Long, index: Map<String, RecMeta>, active: Set<String>): List<String> {
        if (capBytes <= 0) return emptyList()
        val files = list(index, active)
        var total = files.sumOf { it.size }
        val deleted = mutableListOf<String>()
        for (f in files.sortedBy { it.lastModified }) {
            if (total <= capBytes) break
            if (f.inProgress) continue
            if (delete(f)) {
                total -= f.size
                deleted += f.name
                Log.i(TAG, "cap: deleted ${f.name} (${f.size} B)")
            }
        }
        return deleted
    }

    companion object {
        private const val TAG = "RecordingStore"
        private val STAMP: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyy-MM-dd_HHmm")

        fun sanitize(name: String): String = name.replace(Regex("""[\\/:*?"<>|\p{Cntrl}]"""), "").replace(Regex("""\s+"""), " ")
            .trim().take(60).ifEmpty { "Station" }
    }
}
