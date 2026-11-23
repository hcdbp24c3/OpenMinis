package com.openminis.app.tools

import android.content.Context
import java.io.File

/**
 * [T-coding-m2-undo] Keeps what a file looked like just before `file_write` or
 * `file_edit` changed it, so the change can be taken back.
 *
 * One directory per conversation under the app's cache dir: an `index` file
 * (one line per change, oldest first) and a `<seq>.bak` copy per change. It
 * lives in the cache because it is a safety net, not data: the system may clear
 * it, and nothing else depends on it.
 *
 * Scope, stated plainly: only the two file tools are journaled. A change made
 * by a shell command (`sed -i`, `git checkout`, a formatter) is not seen here.
 *
 * No Android types beyond the directory lookup, so the logic is unit-tested on
 * the JVM against a temp dir.
 */
object FileJournal {
    /** Changes remembered per conversation; the oldest fall off. */
    const val MAX_ENTRIES = 40

    /** Files larger than this are not copied; their change cannot be undone. */
    const val MAX_FILE_BYTES = 2_000_000L

    private const val INDEX = "index"
    private const val STATE_CREATED = "0"
    private const val STATE_SAVED = "1"
    private const val STATE_TOO_LARGE = "2"

    data class Entry(val seq: Long, val state: String, val linuxPath: String, val hostPath: String)

    data class UndoResult(val success: Boolean, val message: String)

    fun dir(context: Context, sessionId: String): File {
        val safe = sessionId.map { if (it.isLetterOrDigit() || it == '-' || it == '_') it else '_' }
            .joinToString("").ifEmpty { "default" }
        return File(File(context.cacheDir, "file_journal"), safe)
    }

    private fun fieldOk(s: String): Boolean = s.isNotEmpty() && s.none { it == '\t' || it == '\n' || it == '\r' }

    @Synchronized
    fun entries(root: File): List<Entry> {
        val index = File(root, INDEX)
        if (!index.isFile) return emptyList()
        return index.readLines().mapNotNull { line ->
            val parts = line.split('\t')
            val seq = parts.getOrNull(0)?.toLongOrNull()
            if (parts.size != 4 || seq == null) null else Entry(seq, parts[1], parts[2], parts[3])
        }
    }

    private fun writeIndex(root: File, entries: List<Entry>) {
        root.mkdirs()
        File(root, INDEX).writeText(
            entries.joinToString("") { "${it.seq}\t${it.state}\t${it.linuxPath}\t${it.hostPath}\n" },
        )
    }

    private fun backup(root: File, seq: Long) = File(root, "$seq.bak")

    /**
     * Call right before [hostFile] is written. Never throws: a journal that
     * cannot record must not stop the write it was guarding.
     */
    @Synchronized
    fun record(root: File, linuxPath: String, hostFile: File) {
        try {
            if (!fieldOk(linuxPath) || !fieldOk(hostFile.absolutePath)) return
            if (hostFile.exists() && !hostFile.isFile) return
            root.mkdirs()
            val current = entries(root)
            val seq = (current.maxOfOrNull { it.seq } ?: 0L) + 1L
            val state = when {
                !hostFile.exists() -> STATE_CREATED
                hostFile.length() > MAX_FILE_BYTES -> STATE_TOO_LARGE
                else -> {
                    hostFile.copyTo(backup(root, seq), overwrite = true)
                    STATE_SAVED
                }
            }
            var next = current + Entry(seq, state, linuxPath, hostFile.absolutePath)
            while (next.size > MAX_ENTRIES) {
                backup(root, next.first().seq).delete()
                next = next.drop(1)
            }
            writeIndex(root, next)
        } catch (_: Exception) {
            // Best effort by design.
        }
    }

    /**
     * Take back the most recent journaled change — to [linuxPath] when given,
     * otherwise to whichever file was changed last.
     */
    @Synchronized
    fun undo(root: File, linuxPath: String?): UndoResult {
        val current = entries(root)
        val wanted = linuxPath?.trim().orEmpty()
        val entry = if (wanted.isEmpty()) {
            current.lastOrNull()
        } else {
            current.lastOrNull { it.linuxPath == wanted }
        } ?: return UndoResult(
            false,
            if (wanted.isEmpty()) {
                "Error: nothing to undo. No file_write or file_edit change is recorded for this conversation."
            } else {
                "Error: nothing to undo for $wanted. Only changes made by file_write or file_edit are recorded."
            },
        )

        val host = File(entry.hostPath)
        val message = try {
            when (entry.state) {
                STATE_CREATED -> {
                    if (host.exists() && !host.delete()) {
                        return UndoResult(false, "Error: could not remove ${entry.linuxPath}.")
                    }
                    "Removed ${entry.linuxPath}: it did not exist before that change."
                }
                STATE_SAVED -> {
                    val saved = backup(root, entry.seq)
                    if (!saved.isFile) {
                        return UndoResult(false, "Error: the saved copy of ${entry.linuxPath} is gone (the cache was cleared), so it cannot be restored.")
                    }
                    host.parentFile?.mkdirs()
                    saved.copyTo(host, overwrite = true)
                    "Restored ${entry.linuxPath} to what it was before that change (${host.length()} bytes)."
                }
                else -> return UndoResult(
                    false,
                    "Error: ${entry.linuxPath} was larger than ${MAX_FILE_BYTES / 1_000_000} MB when it was changed, so no copy was kept and it cannot be restored here.",
                )
            }
        } catch (e: Exception) {
            return UndoResult(false, "Error: could not restore ${entry.linuxPath}: ${e.message}")
        }

        backup(root, entry.seq).delete()
        writeIndex(root, current.filter { it.seq != entry.seq })
        val left = current.count { it.linuxPath == entry.linuxPath } - 1
        return UndoResult(
            true,
            message + if (left > 0) " $left earlier change(s) to this file can still be undone." else "",
        )
    }
}
