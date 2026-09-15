package to.trel.internal

import to.trel.Trel

/**
 * Default uncaught-exception handler. Serializes the crash record to disk synchronously (the
 * process is about to die; no thread may run after us), marks the session crashed, then chains to
 * whatever handler was installed before (Crashlytics, the system, …).
 */
internal object CrashHandler : Thread.UncaughtExceptionHandler {
    private var previous: Thread.UncaughtExceptionHandler? = null
    private lateinit var trel: Trel

    fun install(trel: Trel) {
        this.trel = trel
        val current = Thread.getDefaultUncaughtExceptionHandler()
        if (current === this) return
        previous = current
        Thread.setDefaultUncaughtExceptionHandler(this)
    }

    override fun uncaughtException(thread: Thread, throwable: Throwable) {
        try {
            // A fatal JS error already reported by the React Native SDK re-surfaces here as
            // com.facebook.react.common.JavascriptException; count the crash, skip the duplicate.
            val jsDuplicate = trel.jsFatalReported && throwable.javaClass.name.contains("JavascriptException")
            if (!jsDuplicate) {
                val event = trel.buildEvent(throwable, mechanism = "crash", thread = thread)
                trel.enqueueEvent(event, fatal = true, sync = true)
            }
            trel.session.markCrashed()
        } catch (_: Throwable) {
            // never mask the original crash
        } finally {
            previous?.uncaughtException(thread, throwable)
        }
    }
}
