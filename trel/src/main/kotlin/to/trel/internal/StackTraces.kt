package to.trel.internal

import java.io.PrintWriter
import java.io.StringWriter

/** Java-format stack text (`\tat pkg.Class.method(File.kt:12)`), which ingest parses natively. */
internal object StackTraces {
    private const val MAX_CHARS = 64 * 1024

    fun format(t: Throwable): String {
        val sw = StringWriter()
        t.printStackTrace(PrintWriter(sw))
        return sw.toString().take(MAX_CHARS)
    }

    fun format(header: String, frames: Array<StackTraceElement>): String = buildString {
        append(header).append('\n')
        for (f in frames) append("\tat ").append(f.toString()).append('\n')
    }.take(MAX_CHARS)

    /** Stack of the calling thread, minus [skip] internal frames. */
    fun current(skip: Int): String {
        val frames = Thread.currentThread().stackTrace
        return format("Message", frames.drop(skip.coerceAtMost(frames.size)).toTypedArray())
    }

    fun ofThread(thread: Thread, header: String): String = format(header, thread.stackTrace)
}
