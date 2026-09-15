package to.trel.internal

/** Mirror of `ATTR` in `@trel/shared/src/mobile.ts`. Keep the two in sync. */
internal object Attr {
    const val EXCEPTION_TYPE = "exception.type"
    const val EXCEPTION_MESSAGE = "exception.message"
    const val EXCEPTION_STACKTRACE = "exception.stacktrace"
    const val EXCEPTION_ESCAPED = "exception.escaped"
    const val MECHANISM = "trel.mechanism"
    const val THREAD_NAME = "trel.thread.name"
    const val APP_STATE = "trel.app.state"
    const val APP_UPTIME_MS = "trel.app.uptime_ms"
    const val BREADCRUMBS = "trel.breadcrumbs"
    const val DEBUG_META = "trel.debug_meta"
    const val TAG_PREFIX = "trel.tag."
    const val SOURCE = "trel.source"

    const val USER_ID = "enduser.id"
    const val USER_EMAIL = "enduser.email"
    const val USER_NAME = "enduser.name"
    const val SESSION_ID = "session.id"
    const val SESSION_PREVIOUS_ID = "session.previous_id"
    const val SESSION_STATUS = "session.status"
    const val SIGNAL = "trel.signal"

    const val SERVICE_NAME = "service.name"
    const val SERVICE_VERSION = "service.version"
    const val ENVIRONMENT = "deployment.environment"
    const val OS_NAME = "os.name"
    const val OS_VERSION = "os.version"
    const val DEVICE_MODEL = "device.model.identifier"
    const val DEVICE_MANUFACTURER = "device.manufacturer"
    const val DEVICE_ID = "device.id"
    const val SDK_NAME = "telemetry.sdk.name"
    const val SDK_VERSION = "telemetry.sdk.version"
    const val PLATFORM = "trel.platform"
    const val APP_PACKAGE = "trel.app.package"
    const val APP_BUILD = "trel.app.build"

    const val NETWORK_TYPE = "network.connection.type"
    const val BATTERY_LEVEL = "device.battery_level"
    const val FREE_MEMORY = "trel.memory.free_bytes"
    const val ORIENTATION = "trel.orientation"

    // HTTP client spans (OTel semconv)
    const val HTTP_METHOD = "http.request.method"
    const val URL_FULL = "url.full"
    const val HTTP_STATUS = "http.response.status_code"
    const val SERVER_ADDRESS = "server.address"
}
