package to.trel

/**
 * Configuration for [Trel.init]. Only [apiKey] is required.
 *
 * ```kotlin
 * Trel.init(this, TrelOptions(apiKey = "trel_sk_...", environment = "production"))
 * ```
 */
class TrelOptions(
    /** Project ingest key (`trel_sk_…`) from Settings → API keys. */
    val apiKey: String,
    /** Deployment environment; groups issues and release health. */
    val environment: String = "production",
    /** Release identifier. Defaults to `versionName+versionCode` from the package manager. */
    val release: String? = null,
    /** Service name shown in Trel. Defaults to the application id. */
    val service: String? = null,
    /** Ingest base URL. Change only for self-hosted or regional deployments. */
    val endpoint: String = "https://ingest.trel.to",
    /** Report ANRs (watchdog on every API level, `ApplicationExitInfo` on API 30+). */
    val enableAnr: Boolean = true,
    /** Main-thread stall duration before the watchdog reports an ANR. */
    val anrTimeoutMs: Long = 5_000,
    /** Record `http` breadcrumbs from the OkHttp interceptor (spans are always emitted). */
    val enableNetworkBreadcrumbs: Boolean = true,
    /** Also ship breadcrumbs as log records (billed as events). Off by default. */
    val breadcrumbsAsLogs: Boolean = false,
    /** Ring-buffer size for breadcrumbs attached to each exception (max 100). */
    val maxBreadcrumbs: Int = 100,
    /** Capture `Log.e/w` style messages sent through [Trel.log] at or above this level. */
    val minLogLevel: TrelLevel = TrelLevel.DEBUG,
    /** Send `screen <Activity>` spans on activity resume. */
    val enableScreenSpans: Boolean = false,
    /** Print SDK diagnostics to logcat. */
    val debug: Boolean = false,
    /**
     * Last chance to modify or drop an event. Return `null` to drop. Called on the capturing thread;
     * for crashes this is the crashing thread, so keep it fast and allocation-light.
     */
    val beforeSend: ((TrelEvent) -> TrelEvent?)? = null,
) {
    init {
        require(apiKey.isNotBlank()) { "TrelOptions.apiKey must not be blank" }
        require(maxBreadcrumbs in 1..100) { "TrelOptions.maxBreadcrumbs must be between 1 and 100" }
    }
}

/** Severity for [Trel.log] and [Trel.captureMessage]; maps onto OTLP severity numbers. */
enum class TrelLevel(val otlpNumber: Int, val label: String) {
    DEBUG(5, "DEBUG"),
    INFO(9, "INFO"),
    WARN(13, "WARN"),
    ERROR(17, "ERROR"),
    FATAL(21, "FATAL"),
}

/**
 * A breadcrumb: something that happened before an error. Attached (last [TrelOptions.maxBreadcrumbs])
 * to every exception; never billed unless [TrelOptions.breadcrumbsAsLogs] is on.
 */
data class Breadcrumb(
    val message: String,
    /** `ui.lifecycle` · `app.lifecycle` · `http` · `navigation` · `user` · anything you like. */
    val category: String = "default",
    val level: TrelLevel = TrelLevel.INFO,
    val data: Map<String, Any?>? = null,
    val timestampMs: Long = System.currentTimeMillis(),
)

/** Mutable view of an exception before it is queued; what [TrelOptions.beforeSend] receives. */
class TrelEvent internal constructor(
    var type: String,
    var message: String,
    var stacktrace: String,
    val mechanism: String,
    val attributes: MutableMap<String, Any?>,
) {
    /** True for crashes / ANRs that terminate(d) the process. */
    val isFatal: Boolean get() = mechanism == "crash" || mechanism == "anr" || mechanism == "native"
}
