package to.trel.internal

import android.content.Context
import org.json.JSONObject
import to.trel.Trel
import java.io.File

/**
 * Disk-backed queue at `filesDir/trel/queue/<ulid>.<logs|traces>.json`. Each file is one OTLP
 * export body. Records batch in memory (100 records or 2 s, whichever first) before hitting disk;
 * the crash path bypasses batching with [writeLogsNow].
 */
internal class Queue(context: Context) {
    val dir: File = File(context.filesDir, "trel/queue").apply { mkdirs() }
    private val lock = Any()
    private val pendingLogs = ArrayList<JSONObject>()
    private val pendingSpans = ArrayList<JSONObject>()

    @Volatile
    var onDirty: (() -> Unit)? = null

    fun enqueueLog(record: JSONObject) {
        var flushNow: Boolean
        synchronized(lock) {
            pendingLogs.add(record)
            flushNow = pendingLogs.size >= BATCH_SIZE
        }
        if (flushNow) flushMemory() else onDirty?.invoke()
    }

    fun enqueueSpan(span: JSONObject) {
        var flushNow: Boolean
        synchronized(lock) {
            pendingSpans.add(span)
            flushNow = pendingSpans.size >= BATCH_SIZE
        }
        if (flushNow) flushMemory() else onDirty?.invoke()
    }

    /** Moves in-memory batches to disk. Cheap when empty. */
    fun flushMemory() {
        val logs: List<JSONObject>
        val spans: List<JSONObject>
        synchronized(lock) {
            logs = ArrayList(pendingLogs); pendingLogs.clear()
            spans = ArrayList(pendingSpans); pendingSpans.clear()
        }
        if (logs.isNotEmpty()) writeLogsNow(logs)
        if (spans.isNotEmpty()) writeFile("traces", Otlp.tracesBody(Trel.resource.attributes(), spans))
    }

    /** Synchronous write used from the crash handler; must not allocate beyond the JSON itself. */
    fun writeLogsNow(records: List<JSONObject>) {
        writeFile("logs", Otlp.logsBody(Trel.resource.attributes(), records))
    }

    private fun writeFile(kind: String, body: JSONObject) {
        try {
            val tmp = File(dir, "${Ids.ulid()}.$kind.tmp")
            tmp.writeText(body.toString())
            val target = File(dir, tmp.name.removeSuffix(".tmp") + ".json")
            if (!tmp.renameTo(target)) tmp.delete()
            trimIfNeeded()
        } catch (t: Throwable) {
            Trel.debug("queue write failed: $t")
        }
    }

    /** Oldest-first list of files ready to send. */
    fun files(): List<File> = (dir.listFiles { f -> f.name.endsWith(".json") } ?: emptyArray()).sortedBy { it.name }

    fun hasCrashEnvelope(): Boolean = files().any(::isCrashFile)

    /** Keeps the queue under [MAX_FILES] / [MAX_BYTES] by dropping the oldest non-crash files first. */
    private fun trimIfNeeded() {
        val list = files()
        var count = list.size
        var bytes = list.sumOf { it.length() }
        if (count <= MAX_FILES && bytes <= MAX_BYTES) return
        for (f in list) {
            if (count <= MAX_FILES && bytes <= MAX_BYTES) break
            if (isCrashFile(f)) continue
            bytes -= f.length()
            count--
            f.delete()
        }
    }

    private fun isCrashFile(f: File): Boolean =
        f.name.endsWith(".logs.json") && runCatching { f.readText().contains(MARKER_FATAL) }.getOrDefault(false)

    private companion object {
        const val BATCH_SIZE = 100
        const val MAX_FILES = 200
        const val MAX_BYTES = 8L * 1024 * 1024
        /** Present only in fatal records; protects them from trimming and flags a crashed session. */
        const val MARKER_FATAL = "\"exception.escaped\",\"value\":{\"boolValue\":true}"
    }
}
