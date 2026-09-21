package to.trel.internal

import android.app.Activity
import android.app.Application
import android.content.ComponentCallbacks2
import android.content.Context
import android.content.res.Configuration
import android.os.Bundle
import to.trel.Breadcrumb
import to.trel.Trel
import to.trel.TrelLevel

/**
 * Activity lifecycle → `ui.lifecycle` breadcrumbs; foreground/background → `app.lifecycle`
 * breadcrumbs, transport cadence, and optional `screen <Activity>` spans.
 */
internal class Lifecycle(private val context: Context, private val trel: Trel) : Application.ActivityLifecycleCallbacks, ComponentCallbacks2 {
    private var startedActivities = 0
    private var screenStart = 0L
    private var screenName: String? = null

    @Volatile
    var isForeground: Boolean = false
        private set

    @Volatile
    var currentActivity: Activity? = null

    fun install(app: Application) {
        app.registerActivityLifecycleCallbacks(this)
        app.registerComponentCallbacks(this)
    }

    private fun crumb(activity: Activity, state: String) {
        if (trel.options.platform == "react-native") return
        trel.addBreadcrumb(
            Breadcrumb(
                message = "${activity.javaClass.simpleName} $state",
                category = "ui.lifecycle",
                level = TrelLevel.INFO,
                data = mapOf("screen" to activity.javaClass.simpleName, "state" to state),
            ),
        )
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = crumb(activity, "created")

    override fun onActivityStarted(activity: Activity) {
        if (startedActivities++ == 0) {
            isForeground = true
            trel.addBreadcrumb(Breadcrumb("app foregrounded", category = "app.lifecycle"))
            trel.transport.setForeground(true)
        }
        crumb(activity, "started")
    }

    override fun onActivityResumed(activity: Activity) {
        currentActivity = activity
        crumb(activity, "resumed")
        if (trel.options.enableScreenSpans) {
            screenName = activity.javaClass.simpleName
            screenStart = System.currentTimeMillis()
        }
    }

    override fun onActivityPaused(activity: Activity) {
        crumb(activity, "paused")
        val name = screenName
        if (trel.options.enableScreenSpans && name != null) {
            val now = System.currentTimeMillis()
            trel.queue.enqueueSpan(
                Otlp.span(
                    name = "screen $name",
                    kind = Otlp.SPAN_KIND_INTERNAL,
                    traceId = Ids.traceId(),
                    spanId = Ids.spanId(),
                    parentSpanId = null,
                    startMs = screenStart,
                    endMs = now,
                    ok = true,
                    attrs = mapOf("screen.name" to name, Attr.SESSION_ID to trel.session.id),
                ),
            )
            screenName = null
        }
    }

    override fun onActivityStopped(activity: Activity) {
        crumb(activity, "stopped")
        if (--startedActivities <= 0) {
            startedActivities = 0
            isForeground = false
            trel.addBreadcrumb(Breadcrumb("app backgrounded", category = "app.lifecycle"))
            trel.transport.setForeground(false)
        }
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) {
        if (currentActivity === activity) currentActivity = null
        crumb(activity, "destroyed")
    }

    override fun onTrimMemory(level: Int) {
        if (level >= ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) {
            trel.addBreadcrumb(Breadcrumb("low memory (trim level $level)", category = "app.lifecycle", level = TrelLevel.WARN))
        }
    }

    override fun onConfigurationChanged(newConfig: Configuration) {
        trel.addBreadcrumb(Breadcrumb("configuration changed", category = "app.lifecycle", data = mapOf("orientation" to newConfig.orientation)))
    }

    @Deprecated("Deprecated in Java")
    override fun onLowMemory() {
        trel.addBreadcrumb(Breadcrumb("low memory", category = "app.lifecycle", level = TrelLevel.WARN))
    }
}
