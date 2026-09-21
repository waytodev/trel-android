package to.trel.internal

import android.content.Context
import org.json.JSONArray
import org.json.JSONObject
import to.trel.Trel
import to.trel.TrelEvent
import java.io.File

/** Turns a signal-handler crash file from the previous process into an exception on this launch. */
internal object NdkCrashReader {
    fun report(context: Context, trel: Trel) {
        val crash = File(context.filesDir, "trel/ndk-crash.txt")
        if (!crash.exists()) return
        val text = runCatching { crash.readText() }.getOrNull()
        crash.delete()
        if (text.isNullOrBlank()) return
        val sample = runCatching { File(context.filesDir, "trel/thread-sample.txt").readText() }.getOrNull()
        val threads = JSONArray()
        threads.put(JSONObject().put("name", "crashed").put("crashed", true).put("stack", text.take(16_000)))
        if (!sample.isNullOrBlank()) {
            threads.put(JSONObject().put("name", "others").put("crashed", false).put("stack", sample.take(24_000)))
        }
        val attrs = trel.baseAttributes(null)
        attrs.remove(Attr.BREADCRUMBS)
        attrs[Attr.THREADS] = threads.toString().take(48 * 1024)
        val header = text.lineSequence().firstOrNull() ?: "Native crash"
        val event = TrelEvent(
            type = "NativeCrash",
            message = header.take(300),
            stacktrace = text.take(32 * 1024),
            mechanism = "native",
            attributes = attrs,
        )
        trel.enqueueEvent(event, fatal = true, sync = false)
        trel.session.markCrashed()
    }
}
