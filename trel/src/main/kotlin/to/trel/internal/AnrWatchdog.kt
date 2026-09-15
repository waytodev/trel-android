package to.trel.internal

import android.os.Debug
import android.os.Handler
import android.os.Looper
import to.trel.Trel
import to.trel.TrelEvent
import java.util.concurrent.atomic.AtomicLong

/**
 * Main-thread watchdog for API < 30 (and as a fast path everywhere): posts a tick to the main
 * looper every second; if the tick has not run within [timeoutMs] the main thread is stalled and
 * an `ANR` event (mechanism `anr`) is reported once with the main thread's stack. The next report
 * waits for the main thread to recover so one stall produces one event.
 */
internal class AnrWatchdog(private val trel: Trel, private val timeoutMs: Long) : Thread("trel-anr-watchdog") {
    private val main = Handler(Looper.getMainLooper())
    private val lastTick = AtomicLong(System.currentTimeMillis())
    private val tick = Runnable { lastTick.set(System.currentTimeMillis()) }

    @Volatile
    private var reported = false

    init {
        isDaemon = true
    }

    override fun run() {
        while (!isInterrupted) {
            try {
                lastTick.set(System.currentTimeMillis())
                main.post(tick)
                sleep(1_000)
                if (Debug.isDebuggerConnected() || Debug.waitingForDebugger()) continue
                val stalled = System.currentTimeMillis() - lastTick.get()
                if (stalled >= timeoutMs) {
                    if (!reported) {
                        reported = true
                        report(stalled)
                    }
                } else {
                    reported = false
                }
            } catch (_: InterruptedException) {
                return
            } catch (t: Throwable) {
                Trel.debug("anr watchdog error: $t")
            }
        }
    }

    private fun report(stalledMs: Long) {
        val mainThread = Looper.getMainLooper().thread
        val message = "Application Not Responding for at least ${stalledMs / 1000}s"
        val attrs = trel.baseAttributes(null)
        attrs[Attr.THREAD_NAME] = mainThread.name
        val event = TrelEvent(
            type = "ANR",
            message = message,
            stacktrace = StackTraces.ofThread(mainThread, "ANR: $message"),
            mechanism = "anr",
            attributes = attrs,
        )
        // The process usually survives a watchdog ANR (or is killed by the system shortly after),
        // so write synchronously and mark the session so a subsequent kill counts as crashed.
        trel.enqueueEvent(event, fatal = true, sync = true)
        trel.session.markCrashed()
        trel.transport.scheduleSoon(0)
    }
}
