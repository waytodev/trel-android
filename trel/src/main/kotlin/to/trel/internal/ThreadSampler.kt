package to.trel.internal

import to.trel.Trel
import java.io.File

/** Samples every Java thread so a native crash can attach what the other threads were doing. */
internal class ThreadSampler(private val trel: Trel) : Thread("trel-thread-sampler") {
    init { isDaemon = true }

    override fun run() {
        val file = File(trel.queue.dir.parentFile, "thread-sample.txt")
        var lastHash = 0
        while (!isInterrupted) {
            // Sleep outside the work's try: a failing write (full disk) used to spin this loop.
            try {
                sleep(5_000)
            } catch (_: InterruptedException) {
                return
            }
            try {
                val text = buildString {
                    for ((thread, frames) in Thread.getAllStackTraces()) {
                        if (thread.name.startsWith("trel-")) continue
                        append('"').append(thread.name).append("\"\n")
                        for (frame in frames.take(40)) append("\tat ").append(frame).append('\n')
                        append('\n')
                    }
                }.take(48 * 1024)
                val hash = text.hashCode()
                if (hash != lastHash) {
                    file.writeText(text)
                    lastHash = hash
                }
            } catch (t: Throwable) {
                Trel.debug("thread sample: $t")
            }
        }
    }
}
