package to.trel.internal

import org.json.JSONArray
import org.json.JSONObject
import to.trel.Breadcrumb
import to.trel.Trel
import to.trel.TrelEvent
import to.trel.TrelLevel

/** OTLP/JSON builders. Records are `JSONObject`s so the disk queue can store them verbatim. */
internal object Otlp {
    fun anyValue(v: Any?): JSONObject? {
        val o = JSONObject()
        when (v) {
            null -> return null
            is Boolean -> o.put("boolValue", v)
            is Int, is Long, is Short, is Byte -> o.put("intValue", v.toString())
            is Float, is Double -> {
                val d = (v as Number).toDouble()
                // JSONObject.put throws on NaN/Infinity.
                if (d.isFinite()) o.put("doubleValue", d) else o.put("stringValue", d.toString())
            }
            is String -> o.put("stringValue", v.take(MAX_STRING))
            // `toString()` returns null when a nested value is NaN/Infinity.
            is Map<*, *> -> o.put("stringValue", (runCatching { JSONObject(v.mapKeys { it.key.toString() }).toString() }.getOrNull() ?: v.toString()).take(MAX_STRING))
            is Iterable<*> -> o.put("stringValue", (runCatching { JSONArray(v.toList()).toString() }.getOrNull() ?: v.toString()).take(MAX_STRING))
            else -> o.put("stringValue", v.toString().take(MAX_STRING))
        }
        return o
    }

    fun attributes(map: Map<String, Any?>): JSONArray {
        val arr = JSONArray()
        for ((k, v) in map) {
            val value = anyValue(v) ?: continue
            arr.put(JSONObject().put("key", k).put("value", value))
        }
        return arr
    }

    private fun nanos(ms: Long): String = (ms * 1_000_000L).toString()

    fun logRecord(timestampMs: Long, level: TrelLevel, body: String, attrs: Map<String, Any?>): JSONObject {
        val rest = LinkedHashMap(attrs)
        val link = takeTraceLink(rest)
        val record = JSONObject()
            .put("timeUnixNano", nanos(timestampMs))
            .put("severityNumber", level.otlpNumber)
            .put("severityText", level.label)
            .put("body", JSONObject().put("stringValue", body.take(MAX_STRING)))
            .put("attributes", attributes(rest))
        if (link != null) record.put("traceId", link.first).put("spanId", link.second)
        return record
    }

    private val TRACE_KEYS = listOf("trel.trace_id", Attr.TAG_PREFIX + "trace_id")
    private val SPAN_KEYS = listOf("trel.span_id", Attr.TAG_PREFIX + "span_id")
    private val HEX = Regex("^[0-9a-f]+$")

    /**
     * The React Native SDK passes the active trace as `trel.trace_id` / `trel.span_id` and mirrors it
     * into tags, which in-process crashes carry. Lift it to the OTLP top-level fields so the event
     * links to its trace, and drop the attribute copies.
     */
    private fun takeTraceLink(attrs: MutableMap<String, Any?>): Pair<String, String>? {
        val traceId = TRACE_KEYS.firstNotNullOfOrNull { attrs[it] as? String }
        val spanId = SPAN_KEYS.firstNotNullOfOrNull { attrs[it] as? String }
        (TRACE_KEYS + SPAN_KEYS).forEach { attrs.remove(it) }
        if (traceId == null || spanId == null) return null
        if (traceId.length != 32 || spanId.length != 16 || !HEX.matches(traceId) || !HEX.matches(spanId)) return null
        if (traceId.all { it == '0' } || spanId.all { it == '0' }) return null
        return traceId to spanId
    }

    fun exceptionRecord(timestampMs: Long, e: TrelEvent, fatal: Boolean): JSONObject {
        val attrs = LinkedHashMap<String, Any?>(e.attributes)
        attrs[Attr.EXCEPTION_TYPE] = e.type
        attrs[Attr.EXCEPTION_MESSAGE] = e.message
        attrs[Attr.EXCEPTION_STACKTRACE] = e.stacktrace
        attrs[Attr.EXCEPTION_ESCAPED] = fatal
        attrs[Attr.MECHANISM] = e.mechanism
        return logRecord(timestampMs, if (fatal) TrelLevel.FATAL else TrelLevel.ERROR, e.message, attrs)
    }

    fun sessionRecord(sessionId: String, status: String, previousId: String?, timestampMs: Long): JSONObject {
        val attrs = linkedMapOf<String, Any?>(
            Attr.SIGNAL to "session",
            Attr.SESSION_ID to sessionId,
            Attr.SESSION_STATUS to status,
        )
        previousId?.let { attrs[Attr.SESSION_PREVIOUS_ID] = it }
        return logRecord(timestampMs, TrelLevel.INFO, "session", attrs)
    }

    fun span(
        name: String,
        kind: Int,
        traceId: String,
        spanId: String,
        parentSpanId: String?,
        startMs: Long,
        endMs: Long,
        ok: Boolean,
        attrs: Map<String, Any?>,
    ): JSONObject = JSONObject()
        .put("traceId", traceId)
        .put("spanId", spanId)
        .apply { parentSpanId?.let { put("parentSpanId", it) } }
        .put("name", name.take(256))
        .put("kind", kind)
        .put("startTimeUnixNano", nanos(startMs))
        .put("endTimeUnixNano", nanos(maxOf(endMs, startMs)))
        .put("attributes", attributes(attrs))
        .put("status", JSONObject().put("code", if (ok) 1 else 2))

    fun breadcrumbsJson(list: List<Breadcrumb>): String {
        val arr = JSONArray()
        for (b in list) {
            val o = JSONObject()
                .put("ts", b.timestampMs)
                .put("category", b.category)
                .put("message", b.message.take(1000))
                .put("level", when (b.level) { TrelLevel.DEBUG -> "debug"; TrelLevel.INFO -> "info"; TrelLevel.WARN -> "warning"; else -> "error" })
            b.data?.let { d -> o.put("data", JSONObject(d.filterValues { it != null })) }
            arr.put(o)
        }
        return arr.toString()
    }

    /** Wraps records into a full OTLP export request body. */
    fun logsBody(resource: Map<String, Any?>, records: List<JSONObject>): JSONObject = JSONObject().put(
        "resourceLogs",
        JSONArray().put(
            JSONObject()
                .put("resource", JSONObject().put("attributes", attributes(resource)))
                .put("scopeLogs", JSONArray().put(JSONObject().put("scope", scope()).put("logRecords", JSONArray(records)))),
        ),
    )

    fun tracesBody(resource: Map<String, Any?>, spans: List<JSONObject>): JSONObject = JSONObject().put(
        "resourceSpans",
        JSONArray().put(
            JSONObject()
                .put("resource", JSONObject().put("attributes", attributes(resource)))
                .put("scopeSpans", JSONArray().put(JSONObject().put("scope", scope()).put("spans", JSONArray(spans)))),
        ),
    )

    private fun scope() = JSONObject().put("name", Trel.SDK_NAME).put("version", Trel.SDK_VERSION)

    const val SPAN_KIND_INTERNAL = 1
    const val SPAN_KIND_CLIENT = 3
    private const val MAX_STRING = 64 * 1024
}
