package to.trel.internal

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.os.Build
import to.trel.Trel
import to.trel.TrelOptions
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.zip.GZIPOutputStream

/**
 * Sends queued files to `/v1/logs` and `/v1/traces`. Runs on a single background thread:
 * on start, 2 s after new records, every 30 s while foregrounded, on network regain, and on
 * background. 5xx / 429 / IO → exponential backoff (2 s … 5 min), keep file. Other 4xx → drop
 * file. 402 → stop sending for this process (plan limit reached).
 */
internal class Transport(
    private val context: Context,
    private val options: TrelOptions,
    private val queue: Queue,
) {
    private val executor = Executors.newSingleThreadScheduledExecutor { r ->
        Thread(r, "trel-transport").apply { isDaemon = true }
    }
    private val sending = AtomicBoolean(false)
    private var periodic: ScheduledFuture<*>? = null
    private var debounce: ScheduledFuture<*>? = null
    private var backoffMs = 0L
    private var nextAllowedAt = 0L

    @Volatile
    private var disabled = false

    fun start() {
        queue.onDirty = { scheduleSoon(BATCH_DELAY_MS) }
        scheduleSoon(0)
        setForeground(true)
        registerNetworkCallback()
    }

    fun setForeground(foreground: Boolean) {
        periodic?.cancel(false)
        periodic = if (foreground) {
            executor.scheduleWithFixedDelay({ send() }, PERIOD_MS, PERIOD_MS, TimeUnit.MILLISECONDS)
        } else {
            scheduleSoon(0); null
        }
    }

    fun scheduleSoon(delayMs: Long) {
        debounce?.cancel(false)
        debounce = executor.schedule({ send() }, delayMs, TimeUnit.MILLISECONDS)
    }

    fun flushBlocking(timeoutMs: Long) {
        val latch = CountDownLatch(1)
        executor.execute {
            try { send(force = true) } finally { latch.countDown() }
        }
        runCatching { latch.await(timeoutMs, TimeUnit.MILLISECONDS) }
    }

    private fun send(force: Boolean = false) {
        if (disabled) return
        if (!sending.compareAndSet(false, true)) return
        try {
            queue.flushMemory()
            if (!force && System.currentTimeMillis() < nextAllowedAt) return
            for (file in queue.files()) {
                if (disabled) return
                when (post(file)) {
                    Outcome.SENT -> { file.delete(); backoffMs = 0 }
                    Outcome.DROP -> file.delete()
                    Outcome.RETRY -> {
                        backoffMs = if (backoffMs == 0L) MIN_BACKOFF_MS else minOf(backoffMs * 2, MAX_BACKOFF_MS)
                        nextAllowedAt = System.currentTimeMillis() + backoffMs
                        Trel.debug("send failed; retry in ${backoffMs}ms")
                        return
                    }
                    Outcome.DISABLED -> { disabled = true; return }
                }
            }
        } catch (t: Throwable) {
            Trel.debug("send loop error: $t")
        } finally {
            sending.set(false)
        }
    }

    private enum class Outcome { SENT, RETRY, DROP, DISABLED }

    private fun post(file: File): Outcome {
        val path = if (file.name.endsWith(".traces.json")) "/v1/traces" else "/v1/logs"
        val body = try { file.readBytes() } catch (_: Throwable) { return Outcome.DROP }
        if (body.isEmpty()) return Outcome.DROP
        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(options.endpoint.trimEnd('/') + path).openConnection() as HttpURLConnection).apply {
                requestMethod = "POST"
                connectTimeout = 10_000
                readTimeout = 15_000
                doOutput = true
                setRequestProperty("Content-Type", "application/json")
                setRequestProperty("Content-Encoding", "gzip")
                setRequestProperty("x-trel-key", options.apiKey)
                setRequestProperty("x-trel-environment", options.environment)
                setRequestProperty("x-trel-release", Trel.resource.release)
                setRequestProperty("x-trel-sdk", "${Trel.SDK_NAME}/${Trel.SDK_VERSION}")
                setRequestProperty("User-Agent", "${Trel.SDK_NAME}/${Trel.SDK_VERSION} (Android ${Build.VERSION.RELEASE})")
            }
            GZIPOutputStream(conn.outputStream).use { it.write(body) }
            val code = conn.responseCode
            runCatching { (if (code >= 400) conn.errorStream else conn.inputStream)?.use { it.readBytes() } }
            when {
                code in 200..299 -> Outcome.SENT
                code == 402 -> { Trel.debug("plan limit reached (402); sending disabled for this process"); Outcome.DISABLED }
                code == 429 || code >= 500 -> Outcome.RETRY
                code == 401 || code == 403 -> { Trel.debug("rejected ($code): check apiKey"); Outcome.DROP }
                else -> Outcome.DROP
            }
        } catch (_: Throwable) {
            Outcome.RETRY
        } finally {
            conn?.disconnect()
        }
    }

    private fun registerNetworkCallback() {
        if (Build.VERSION.SDK_INT < 24) return
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
            cm.registerNetworkCallback(
                NetworkRequest.Builder().build(),
                object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        nextAllowedAt = 0
                        scheduleSoon(500)
                    }
                },
            )
        }
    }

    private companion object {
        const val BATCH_DELAY_MS = 2_000L
        const val PERIOD_MS = 30_000L
        const val MIN_BACKOFF_MS = 2_000L
        const val MAX_BACKOFF_MS = 5 * 60_000L
    }
}
