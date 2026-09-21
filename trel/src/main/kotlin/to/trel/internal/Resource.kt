package to.trel.internal

import android.app.ActivityManager
import android.content.Context
import android.content.Context.BATTERY_SERVICE
import android.content.res.Configuration
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Build
import android.os.SystemClock
import android.util.DisplayMetrics
import to.trel.Trel
import to.trel.TrelOptions
import java.util.Locale
import java.util.TimeZone
import java.util.UUID

/** Resource attributes for every payload plus best-effort device state per event. */
internal class Resource private constructor(
    private val context: Context,
    val serviceName: String,
    val release: String,
    val appPackage: String,
    val appBuild: String?,
    val environment: String,
    private val installId: String,
    private val platform: String,
    private val sdkName: String,
) {
    private val processStart = SystemClock.elapsedRealtime()

    /** OTLP resource attributes as key → value. */
    fun attributes(): Map<String, Any?> = linkedMapOf(
        Attr.SERVICE_NAME to serviceName,
        Attr.SERVICE_VERSION to release,
        Attr.ENVIRONMENT to environment,
        Attr.OS_NAME to "android",
        Attr.OS_VERSION to Build.VERSION.RELEASE,
        Attr.DEVICE_MODEL to Build.MODEL,
        Attr.DEVICE_MANUFACTURER to Build.MANUFACTURER,
        Attr.DEVICE_ID to installId,
        Attr.SDK_NAME to sdkName,
        Attr.SDK_VERSION to Trel.SDK_VERSION,
        Attr.PLATFORM to platform,
        Attr.APP_PACKAGE to appPackage,
        Attr.APP_BUILD to appBuild,
        "os.api_level" to Build.VERSION.SDK_INT,
    )

    fun uptimeMs(): Long = SystemClock.elapsedRealtime() - processStart

    /** Network / battery / memory / orientation; every read is wrapped because OEMs throw. */
    fun deviceState(into: MutableMap<String, Any?>) {
        runCatching {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
            if (cm != null) {
                val type = if (Build.VERSION.SDK_INT >= 23) {
                    val caps = cm.getNetworkCapabilities(cm.activeNetwork)
                    when {
                        caps == null -> "none"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cell"
                        caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "wired"
                        else -> "other"
                    }
                } else {
                    @Suppress("DEPRECATION")
                    when (cm.activeNetworkInfo?.type) {
                        null -> "none"
                        ConnectivityManager.TYPE_WIFI -> "wifi"
                        ConnectivityManager.TYPE_MOBILE -> "cell"
                        else -> "other"
                    }
                }
                into[Attr.NETWORK_TYPE] = type
            }
        }
        runCatching {
            val bm = context.getSystemService(BATTERY_SERVICE) as? BatteryManager
            val level = bm?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
            if (level in 0..100) into[Attr.BATTERY_LEVEL] = level
        }
        runCatching {
            val am = context.getSystemService(Context.ACTIVITY_SERVICE) as? ActivityManager
            val mi = ActivityManager.MemoryInfo()
            am?.getMemoryInfo(mi)
            into[Attr.FREE_MEMORY] = mi.availMem
            into[Attr.TOTAL_MEMORY] = mi.totalMem
        }
        runCatching {
            val sticky = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            val status = sticky?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
            into[Attr.BATTERY_CHARGING] = status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL
        }
        runCatching {
            val dm: DisplayMetrics = context.resources.displayMetrics
            into[Attr.SCREEN] = "${dm.widthPixels}x${dm.heightPixels}@${dm.density}"
        }
        runCatching { into[Attr.LOCALE] = Locale.getDefault().toLanguageTag() }
        runCatching { into[Attr.TIMEZONE] = TimeZone.getDefault().id }
        runCatching { into[Attr.ARCH] = Build.SUPPORTED_ABIS.firstOrNull() ?: "unknown" }
        runCatching {
            val fp = Build.FINGERPRINT + Build.MODEL + Build.PRODUCT
            into[Attr.SIMULATOR] = fp.contains("generic", true) || fp.contains("emulator", true) || fp.contains("sdk", true)
        }
        runCatching {
            into[Attr.ORIENTATION] = when (context.resources.configuration.orientation) {
                Configuration.ORIENTATION_LANDSCAPE -> "landscape"
                Configuration.ORIENTATION_PORTRAIT -> "portrait"
                else -> "unknown"
            }
        }
    }

    companion object {
        private const val PREFS = "to.trel"
        private const val KEY_INSTALL_ID = "install_id"

        fun build(context: Context, options: TrelOptions): Resource {
            val pkg = context.packageName
            var versionName: String? = null
            var versionCode: String? = null
            runCatching {
                val info = context.packageManager.getPackageInfo(pkg, 0)
                versionName = info.versionName
                versionCode = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toString() else @Suppress("DEPRECATION") info.versionCode.toString()
            }
            val release = options.release ?: buildString {
                append(versionName ?: "0.0.0")
                versionCode?.let { append('+').append(it) }
            }
            val prefs = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            var installId = prefs.getString(KEY_INSTALL_ID, null)
            if (installId == null) {
                installId = UUID.randomUUID().toString()
                prefs.edit().putString(KEY_INSTALL_ID, installId).apply()
            }
            return Resource(
                context = context,
                serviceName = options.service ?: pkg,
                release = release,
                appPackage = pkg,
                appBuild = versionCode,
                environment = options.environment,
                installId = installId,
                platform = options.platform,
                sdkName = options.sdkName,
            )
        }
    }
}
