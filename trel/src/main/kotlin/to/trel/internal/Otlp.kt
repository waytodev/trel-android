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
            is Float, is Double -> o.put("doubleValue", (v as Number).toDouble())
            is String -> o.put("stringValue", v.take(MAX_STRING))
            is Map<*, *> -> o.put("stringValue", JSONObject(v.mapKeys { it.key.toString() }).toString().take(MAX_STRING))
            is Iterable<*> -> o.put("stringValue", JSONArray(v.toList()).toString().take(MAX_STRING))
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

    fun logRecord(timestampMs: Long, level: TrelLevel, body: String, attrs: Map<String, Any?>): JSONObject =
        JSONObject()
            .put("timeUnixNano", nanos(timestampMs))
            .put("severityNumber", level.otlpNumber)
            .put("severityText", level.label)
            .put("body", JSONObject().put("stringValue", body.take(MAX_STRING)))
            .put("attributes", attributes(attrs))

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
