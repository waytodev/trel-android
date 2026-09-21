package to.trel.internal

import android.app.ActivityManager
import android.app.ApplicationExitInfo
import android.content.Context
import android.os.Build
import to.trel.Trel
import to.trel.TrelEvent

/**
 * API 30+: reads `ApplicationExitInfo` for the previous process and synthesizes exception events
 * for system-detected ANRs (`REASON_ANR`, with the ANR trace) and native crashes
 * (`REASON_CRASH_NATIVE`, API 31+ tombstone). Exits are deduplicated by timestamp in prefs.
 */
internal object ExitInfoReader {
    private const val PREFS = "to.trel.exitinfo"
    private const val KEY_LAST_TS = "last_exit_ts"
    private const val MAX_TRACE_BYTES = 64 * 1024

    fun report(context: Context, trel: Trel) {
        if (Build.VERSION.SDK_INT < 30) return
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager ?: return
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            val lastTs = prefs.getLong(KEY_LAST_TS, 0L)
            val exits = am.getHistoricalProcessExitReasons(context.packageName, 0, 10)
            var newest = lastTs
            for (exit in exits) {
                if (exit.timestamp <= lastTs) continue
                if (exit.timestamp > newest) newest = exit.timestamp
                when (exit.reason) {
                    ApplicationExitInfo.REASON_ANR -> emit(trel, exit, "ANR", "anr", exit.description ?: "Application Not Responding")
                    ApplicationExitInfo.REASON_CRASH_NATIVE -> emit(trel, exit, "NativeCrash", "native", exit.description ?: "Native crash")
                    ApplicationExitInfo.REASON_LOW_MEMORY -> emit(trel, exit, "OutOfMemory", "oom", exit.description ?: "Low memory")
                    ApplicationExitInfo.REASON_SIGNALED,
                    ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE,
                    -> emit(trel, exit, "Watchdog", "watchdog", exit.description ?: "Process killed")
                    // REASON_CRASH (Java) is already captured by CrashHandler before the process died.
                    else -> Unit
                }
            }
            if (newest != lastTs) prefs.edit().putLong(KEY_LAST_TS, newest).apply()
        }.onFailure { Trel.debug("exit info read failed: $it") }
    }

    private fun emit(trel: Trel, exit: ApplicationExitInfo, type: String, mechanism: String, message: String) {
        val trace = readTrace(exit)
        val attrs = trel.baseAttributes(null)
        attrs.remove(Attr.BREADCRUMBS) // breadcrumbs belong to this launch, not the dead process
        attrs["trel.exit.reason"] = exit.reason
        attrs["trel.exit.importance"] = exit.importance
        attrs["trel.exit.pss_kb"] = exit.pss
        if (trace != null && mechanism == "anr") attrs[Attr.THREADS] = threadsJson(trace)
        val primary = if (mechanism == "anr") mainThreadSection(trace ?: "") else trace
        val event = TrelEvent(
            type = type,
            message = message.take(500),
            stacktrace = (primary ?: "$type: $message").take(32 * 1024),
            mechanism = mechanism,
            attributes = attrs,
        )
        trel.enqueueEvent(event, fatal = true, sync = false)
        trel.session.markCrashed()
    }

    /** ANR trace / tombstone text, trimmed to the interesting part (first 64 KB). */
    private fun readTrace(exit: ApplicationExitInfo): String? = runCatching {
        exit.traceInputStream?.use { input ->
            val buf = ByteArray(MAX_TRACE_BYTES)
            var read = 0
            while (read < buf.size) {
                val n = input.read(buf, read, buf.size - read)
                if (n < 0) break
                read += n
            }
            if (read == 0) null else String(buf, 0, read, Charsets.UTF_8)
        }
    }.getOrNull()?.let { mainThreadSection(it) }

    /** One JSON entry per `"thread"` block. Capped so the attribute stays under the OTLP string limit. */
    private fun threadsJson(trace: String): String {
        val arr = org.json.JSONArray()
        val parts = trace.split(Regex("(?=\n\")"))
        for (part in parts) {
            if (arr.length() >= 32) break
            val name = Regex("\"([^\"]+)\"").find(part)?.groupValues?.getOrNull(1) ?: continue
            arr.put(org.json.JSONObject().put("name", name).put("crashed", name == "main").put("stack", part.take(4000)))
        }
        return arr.toString().take(48 * 1024)
    }

    /** ANR traces list every thread; the main thread block is what groups the issue. */
    private fun mainThreadSection(trace: String): String {
        val start = trace.indexOf("\"main\"")
        if (start < 0) return trace
        val end = trace.indexOf("\n\n", start)
        return if (end > start) trace.substring(start, end) else trace.substring(start)
    }
}
