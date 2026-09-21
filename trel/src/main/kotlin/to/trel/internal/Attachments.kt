package to.trel.internal

import android.content.Context
import org.json.JSONObject
import to.trel.Trel
import java.io.File
import java.util.UUID

/** Disk queue for screenshots, replay frames, and profiles. Uploaded by [Transport], not as OTLP events. */
internal class Attachments(context: Context) {
    val dir: File = File(context.filesDir, "trel/attachments").apply { mkdirs() }

    fun enqueue(type: String, body: ByteArray, eventId: String?, traceId: String?, sessionId: String?, fatal: Boolean, forcedId: String? = null): String {
        val capped = when (type) {
            "profile" -> body.copyOf(minOf(body.size, 1024 * 1024))
            else -> body.copyOf(minOf(body.size, 200 * 1024))
        }
        val id = forcedId?.takeIf { it.isNotBlank() } ?: UUID.randomUUID().toString()
        val meta = JSONObject()
            .put("id", id)
            .put("type", type)
            .put("ts", System.currentTimeMillis())
            .put("fatal", fatal)
        eventId?.let { meta.put("eventId", it) }
        traceId?.let { meta.put("traceId", it) }
        sessionId?.let { meta.put("sessionId", it) }
        File(dir, "$id.json").writeText(meta.toString())
        File(dir, "$id.bin").writeBytes(capped)
        if (!fatal) trim()
        Trel.transport.scheduleSoon(2_000)
        return id
    }

    fun pending(): List<Pair<JSONObject, File>> {
        val metas = dir.listFiles { f -> f.name.endsWith(".json") } ?: return emptyList()
        return metas.mapNotNull { metaFile ->
            val bin = File(dir, metaFile.name.removeSuffix(".json") + ".bin")
            if (!bin.exists()) return@mapNotNull null
            val obj = runCatching { JSONObject(metaFile.readText()) }.getOrNull() ?: return@mapNotNull null
            obj to bin
        }
    }

    fun delete(id: String) {
        File(dir, "$id.json").delete()
        File(dir, "$id.bin").delete()
    }

    private fun trim() {
        val bins = dir.listFiles { f -> f.name.endsWith(".bin") }?.sortedBy { it.lastModified() } ?: return
        var bytes = bins.sumOf { it.length() }
        for (bin in bins) {
            if (bins.size <= 40 && bytes <= 8L * 1024 * 1024) return
            val id = bin.name.removeSuffix(".bin")
            val fatal = runCatching { JSONObject(File(dir, "$id.json").readText()).optBoolean("fatal") }.getOrDefault(false)
            if (fatal) continue
            bytes -= bin.length()
            delete(id)
        }
    }
}
