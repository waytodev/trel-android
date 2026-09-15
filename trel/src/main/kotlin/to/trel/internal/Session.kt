package to.trel.internal

import android.content.Context
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/**
 * One session per process launch. `started` on init; `errored` once on the first handled error;
 * `crashed` on the next launch when the previous run left a dirty marker (crash handler ran or the
 * process died with a queued fatal). The dirty marker is cleared on clean background/exit.
 */
internal class Session(context: Context, private val resource: Resource) {
    private val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
    val id: String = UUID.randomUUID().toString()
    private val errored = AtomicBoolean(false)

    /** Emits `crashed` for the previous session if it ended in a crash / ANR; then rotates markers. */
    fun reportPreviousRun(queue: Queue) {
        val previousId = prefs.getString(KEY_SESSION_ID, null)
        val crashed = prefs.getBoolean(KEY_CRASHED, false) || queue.hasCrashEnvelope()
        if (previousId != null && crashed) {
            queue.enqueueLog(Otlp.sessionRecord(previousId, "crashed", null, System.currentTimeMillis()))
        }
        prefs.edit().putString(KEY_SESSION_ID, id).putBoolean(KEY_CRASHED, false).apply()
    }

    fun start(queue: Queue) {
        queue.enqueueLog(Otlp.sessionRecord(id, "started", prefs.getString(KEY_PREVIOUS_ID, null), System.currentTimeMillis()))
        prefs.edit().putString(KEY_PREVIOUS_ID, id).apply()
    }

    fun markErrored(queue: Queue) {
        if (errored.compareAndSet(false, true)) {
            queue.enqueueLog(Otlp.sessionRecord(id, "errored", null, System.currentTimeMillis()))
        }
    }

    /** Synchronous (commit) so the flag survives the process being killed right after. */
    fun markCrashed() {
        prefs.edit().putBoolean(KEY_CRASHED, true).commit()
    }

    val release: String get() = resource.release

    private companion object {
        const val PREFS = "to.trel.session"
        const val KEY_SESSION_ID = "session_id"
        const val KEY_PREVIOUS_ID = "previous_session_id"
        const val KEY_CRASHED = "crashed"
    }
}
