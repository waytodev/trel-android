package to.trel

import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri

/**
 * Starts Trel before `Application.onCreate` when the host manifest sets `to.trel.API_KEY`.
 * Apps that only call `Trel.init` from JavaScript still work: this provider no-ops without the key.
 */
class TrelInitProvider : ContentProvider() {
    override fun onCreate(): Boolean {
        val app = context?.applicationContext ?: return false
        val key = runCatching {
            val info = app.packageManager.getApplicationInfo(app.packageName, PackageManager.GET_META_DATA)
            info.metaData?.getString(META_KEY)
        }.getOrNull()
        if (key.isNullOrBlank() || Trel.isStarted) return true
        val meta = runCatching {
            app.packageManager.getApplicationInfo(app.packageName, PackageManager.GET_META_DATA).metaData
        }.getOrNull()
        Trel.init(
            app,
            TrelOptions(
                apiKey = key,
                environment = meta?.getString(META_ENV) ?: "production",
                release = meta?.getString(META_RELEASE),
                platform = meta?.getString(META_PLATFORM) ?: "android",
            ),
        )
        return true
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        const val META_KEY = "to.trel.API_KEY"
        const val META_ENV = "to.trel.ENVIRONMENT"
        const val META_RELEASE = "to.trel.RELEASE"
        const val META_PLATFORM = "to.trel.PLATFORM"
    }
}
