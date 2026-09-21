package to.trel.internal

import java.io.File

internal object Ndk {
    fun install(crashFile: File) {
        crashFile.parentFile?.mkdirs()
        runCatching {
            System.loadLibrary("trelndk")
            nativeInstall(crashFile.absolutePath)
        }.onFailure { to.trel.Trel.debug("ndk install skipped: $it") }
    }

    private external fun nativeInstall(path: String)
}
