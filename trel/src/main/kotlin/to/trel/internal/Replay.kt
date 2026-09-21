package to.trel.internal

import to.trel.Trel
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Foreground screenshot every second while this process was sampled in. */
internal object Replay {
    @Volatile
    var paused: Boolean = false
    private val started = AtomicBoolean(false)
    private val executor = Executors.newSingleThreadScheduledExecutor { r -> Thread(r, "trel-replay").apply { isDaemon = true } }

    fun start(trel: Trel) {
        if (!started.compareAndSet(false, true)) return
        executor.scheduleWithFixedDelay({
            try {
                if (paused || !trel.lifecycle.isForeground) return@scheduleWithFixedDelay
                val bytes = Screenshot.capture(trel.lifecycle, trel.options.maskAllText) ?: return@scheduleWithFixedDelay
                trel.attachments.enqueue("replay", bytes, null, null, trel.session.id, false)
            } catch (t: Throwable) {
                Trel.debug("replay: $t")
            }
        }, 1, 1, TimeUnit.SECONDS)
    }
}
