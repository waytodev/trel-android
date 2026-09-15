package to.trel

import android.app.Application
import android.content.Context
import android.util.Log
import to.trel.internal.AnrWatchdog
import to.trel.internal.Attr
import to.trel.internal.CrashHandler
import to.trel.internal.ExitInfoReader
import to.trel.internal.Lifecycle
import to.trel.internal.Otlp
import to.trel.internal.Queue
import to.trel.internal.Resource
import to.trel.internal.Scope
import to.trel.internal.Session
import to.trel.internal.StackTraces
import to.trel.internal.Transport
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Trel for Android: crashes, ANRs, handled errors, breadcrumbs, sessions, HTTP spans and logs.
 *
 * ```kotlin
 * class App : Application() {
 *   override fun onCreate() {
 *     super.onCreate()
 *     Trel.init(this, TrelOptions(apiKey = BuildConfig.TREL_KEY, environment = "production"))
 *   }
 * }
 * ```
 *
 * Everything is written to a disk queue first and shipped in the background, so a crash on the
 * very first frame still arrives on the next launch. All methods are safe to call from any thread
 * and are no-ops before [init].
 */
object Trel {
    internal const val SDK_NAME = "trel-android"
    internal val SDK_VERSION: String = BuildConfig.SDK_VERSION
    internal const val TAG = "Trel"

    private val started = AtomicBoolean(false)

    internal lateinit var options: TrelOptions
    internal lateinit var resource: Resource
    internal lateinit var scope: Scope
    internal lateinit var queue: Queue
    internal lateinit var transport: Transport
    internal lateinit var session: Session
    internal lateinit var lifecycle: Lifecycle
    private var anrWatchdog: AnrWatchdog? = null

    val isStarted: Boolean get() = started.get()

    @JvmStatic
    fun init(context: Context, options: TrelOptions) {
        if (!started.compareAndSet(false, true)) {
            debug("init called twice; ignoring")
            return
        }
        val app = context.applicationContext
        this.options = options
        resource = Resource.build(app, options)
        scope = Scope(options.maxBreadcrumbs)
        queue = Queue(app)
        session = Session(app, resource)
        transport = Transport(app, options, queue)
        lifecycle = Lifecycle(app, this)

        // Previous-run artifacts first: crash envelopes are already on disk; `crashed` session
        // signal and ApplicationExitInfo (ANR / native) reports get synthesized here.
        session.reportPreviousRun(queue)
        if (options.enableAnr) ExitInfoReader.report(app, this)
        session.start(queue)

        CrashHandler.install(this)
        if (options.enableAnr) anrWatchdog = AnrWatchdog(this, options.anrTimeoutMs).also { it.start() }
        if (app is Application) lifecycle.install(app)

        transport.start()
        debug("started v$SDK_VERSION release=${resource.release} env=${options.environment}")
    }

    /* ----------------------------------------------------------------- capture */

    /** Reports a handled exception. Returns immediately; delivery is asynchronous. */
    @JvmStatic
    @JvmOverloads
    fun captureException(throwable: Throwable, attributes: Map<String, Any?>? = null) {
        if (!isStarted) return
        val event = buildEvent(throwable, mechanism = "handled", attributes = attributes)
        enqueueEvent(event, fatal = false, sync = false)
        session.markErrored(queue)
    }

    /** Reports a message as an exception-less issue (grouped by message). */
    @JvmStatic
    @JvmOverloads
    fun captureMessage(message: String, level: TrelLevel = TrelLevel.INFO, attributes: Map<String, Any?>? = null) {
        if (!isStarted) return
        if (level.otlpNumber >= TrelLevel.ERROR.otlpNumber) {
            val event = TrelEvent(
                type = "Message",
                message = message,
                stacktrace = StackTraces.current(skip = 3),
                mechanism = "handled",
                attributes = baseAttributes(attributes),
            )
            enqueueEvent(event, fatal = false, sync = false)
        } else {
            log(level, message, attributes)
        }
    }

    /** Ships a log record (billed as an event). Use [addBreadcrumb] for free context instead. */
    @JvmStatic
    @JvmOverloads
    fun log(level: TrelLevel, message: String, attributes: Map<String, Any?>? = null) {
        if (!isStarted || level.otlpNumber < options.minLogLevel.otlpNumber) return
        val attrs = LinkedHashMap<String, Any?>()
        attributes?.let { attrs.putAll(it) }
        scope.user.applyTo(attrs)
        attrs[Attr.SESSION_ID] = session.id
        queue.enqueueLog(Otlp.logRecord(System.currentTimeMillis(), level, message, attrs))
    }

    @JvmStatic
    fun addBreadcrumb(breadcrumb: Breadcrumb) {
        if (!isStarted) return
        scope.addBreadcrumb(breadcrumb)
        if (options.breadcrumbsAsLogs) {
            val attrs = LinkedHashMap<String, Any?>()
            attrs["breadcrumb.category"] = breadcrumb.category
            breadcrumb.data?.forEach { (k, v) -> attrs["breadcrumb.$k"] = v }
            attrs[Attr.SESSION_ID] = session.id
            queue.enqueueLog(Otlp.logRecord(breadcrumb.timestampMs, breadcrumb.level, breadcrumb.message, attrs))
        }
    }

    @JvmStatic
    @JvmOverloads
    fun addBreadcrumb(message: String, category: String = "default", data: Map<String, Any?>? = null) =
        addBreadcrumb(Breadcrumb(message = message, category = category, data = data))

    /** Identifies the current user; drives "users affected". Pass `null` id to clear. */
    @JvmStatic
    @JvmOverloads
    fun setUser(id: String?, email: String? = null, name: String? = null) {
        if (!isStarted) return
        scope.setUser(id, email, name)
    }

    /** Free-form tag attached to every exception as `trel.tag.<key>` (max 20). */
    @JvmStatic
    fun setTag(key: String, value: String?) {
        if (!isStarted) return
        scope.setTag(key, value)
    }

    /** Blocks (up to [timeoutMs]) until the queue has been sent. Call before `System.exit`. */
    @JvmStatic
    @JvmOverloads
    fun flush(timeoutMs: Long = 3_000) {
        if (!isStarted) return
        transport.flushBlocking(timeoutMs)
    }

    /** Current session id (per process launch). */
    @JvmStatic
    val sessionId: String? get() = if (isStarted) session.id else null

    /* ------------------------------------------------------------- http spans */

    /**
     * Starts an HTTP CLIENT span for a request you are about to make. Add [HttpSpan.traceparent]
     * as a request header so the server's spans join the same trace, then call [HttpSpan.finish].
     * `trel-okhttp` does this for you; use it directly for Ktor, Volley, or raw `HttpURLConnection`.
     */
    @JvmStatic
    fun startHttpSpan(method: String, url: String): HttpSpan = HttpSpan(method, url)

    class HttpSpan internal constructor(private val method: String, url: String) {
        private val traceId = to.trel.internal.Ids.traceId()
        private val spanId = to.trel.internal.Ids.spanId()
        private val startMs = System.currentTimeMillis()
        private val url = url.substringBefore('?')
        private val host = runCatching { java.net.URI(this.url).host }.getOrNull() ?: ""
        private val path = runCatching { java.net.URI(this.url).path }.getOrNull().orEmpty().ifEmpty { "/" }
        private var finished = false

        /** W3C `traceparent` header value for this span. */
        val traceparent: String get() = "00-$traceId-$spanId-01"

        /** Records the span (and an `http` breadcrumb when enabled). Safe to call once. */
        @JvmOverloads
        fun finish(statusCode: Int = 0, error: Throwable? = null) {
            if (finished || !isStarted) return
            finished = true
            val end = System.currentTimeMillis()
            val attrs = linkedMapOf<String, Any?>(
                Attr.HTTP_METHOD to method,
                Attr.URL_FULL to url,
                Attr.SERVER_ADDRESS to host,
                Attr.SESSION_ID to session.id,
            )
            if (statusCode > 0) attrs[Attr.HTTP_STATUS] = statusCode
            error?.let { attrs["error.type"] = it.javaClass.simpleName }
            queue.enqueueSpan(
                Otlp.span(
                    name = "$method $path",
                    kind = Otlp.SPAN_KIND_CLIENT,
                    traceId = traceId,
                    spanId = spanId,
                    parentSpanId = null,
                    startMs = startMs,
                    endMs = end,
                    ok = error == null && statusCode < 500,
                    attrs = attrs,
                ),
            )
            if (options.enableNetworkBreadcrumbs) {
                val outcome = if (statusCode > 0) statusCode.toString() else error?.javaClass?.simpleName ?: ""
                addBreadcrumb(
                    Breadcrumb(
                        message = "$method $url $outcome".trim(),
                        category = "http",
                        level = when {
                            error != null || statusCode >= 500 -> TrelLevel.ERROR
                            statusCode >= 400 -> TrelLevel.WARN
                            else -> TrelLevel.INFO
                        },
                        data = mapOf("method" to method, "url" to url, "status" to statusCode, "duration_ms" to (end - startMs)),
                    ),
                )
            }
        }
    }

    /* ---------------------------------------------------------------- internal */

    internal fun buildEvent(throwable: Throwable, mechanism: String, attributes: Map<String, Any?>? = null, thread: Thread? = null): TrelEvent {
        val attrs = baseAttributes(attributes)
        attrs[Attr.THREAD_NAME] = (thread ?: Thread.currentThread()).name
        return TrelEvent(
            type = throwable.javaClass.name,
            message = throwable.message ?: throwable.javaClass.simpleName,
            stacktrace = StackTraces.format(throwable),
            mechanism = mechanism,
            attributes = attrs,
        )
    }

    internal fun baseAttributes(extra: Map<String, Any?>?): MutableMap<String, Any?> {
        val attrs = LinkedHashMap<String, Any?>()
        extra?.forEach { (k, v) -> attrs[k] = v }
        scope.user.applyTo(attrs)
        scope.tags.forEach { (k, v) -> attrs[Attr.TAG_PREFIX + k] = v }
        attrs[Attr.SESSION_ID] = session.id
        attrs[Attr.APP_STATE] = if (lifecycle.isForeground) "foreground" else "background"
        attrs[Attr.APP_UPTIME_MS] = resource.uptimeMs()
        attrs[Attr.BREADCRUMBS] = Otlp.breadcrumbsJson(scope.breadcrumbs())
        resource.deviceState(attrs)
        return attrs
    }

    /**
     * Applies `beforeSend` and writes the exception record. `sync` forces a synchronous disk
     * write (crash path); otherwise the record joins the in-memory batch.
     */
    internal fun enqueueEvent(event: TrelEvent, fatal: Boolean, sync: Boolean) {
        val finalEvent = try {
            options.beforeSend?.let { it(event) ?: return } ?: event
        } catch (t: Throwable) {
            debug("beforeSend threw: $t"); event
        }
        val record = Otlp.exceptionRecord(System.currentTimeMillis(), finalEvent, fatal)
        if (sync) queue.writeLogsNow(listOf(record)) else queue.enqueueLog(record)
    }

    internal fun debug(msg: String) {
        if (::options.isInitialized && options.debug) Log.d(TAG, msg)
    }
}
