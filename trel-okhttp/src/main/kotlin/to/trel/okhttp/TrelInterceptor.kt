package to.trel.okhttp

import okhttp3.Interceptor
import okhttp3.Response
import to.trel.Trel
import java.io.IOException

/**
 * OkHttp interceptor: one HTTP CLIENT span per request (shows up in Trel's API performance
 * table) plus an `http` breadcrumb. Injects `traceparent` so server spans join the same trace.
 *
 * ```kotlin
 * OkHttpClient.Builder().addInterceptor(TrelInterceptor()).build()
 * ```
 */
class TrelInterceptor @JvmOverloads constructor(
    /** Hosts to skip entirely (the Trel ingest host is always skipped). */
    private val ignoreHosts: Set<String> = emptySet(),
) : Interceptor {

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val host = request.url.host
        if (!Trel.isStarted || host in ignoreHosts || host.endsWith("trel.to")) return chain.proceed(request)

        val span = Trel.startHttpSpan(request.method, request.url.toString())
        val traced = request.newBuilder().header("traceparent", span.traceparent).build()
        try {
            val response = chain.proceed(traced)
            span.finish(statusCode = response.code)
            return response
        } catch (e: IOException) {
            span.finish(error = e)
            throw e
        }
    }
}
