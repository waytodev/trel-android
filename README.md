# Trel Android SDK

Crashes, ANRs, handled errors, breadcrumbs, sessions, HTTP spans and logs for Android — sent to
[Trel](https://trel.to) over OTLP. No third-party runtime dependencies (`HttpURLConnection` + `org.json`).

```kotlin
// build.gradle.kts
dependencies {
    implementation("to.trel:trel:0.1.0")
    implementation("to.trel:trel-okhttp:0.1.0") // optional: HTTP client spans
}
```

```kotlin
class App : Application() {
    override fun onCreate() {
        super.onCreate()
        Trel.init(this, TrelOptions(apiKey = BuildConfig.TREL_KEY, environment = "production"))
    }
}
```

## What is captured

| Signal | Mechanism | How |
| --- | --- | --- |
| Uncaught exceptions | `crash` | `Thread.setDefaultUncaughtExceptionHandler`; written to disk synchronously, sent on next launch |
| ANR | `anr` | Main-thread watchdog (all API levels, 5 s default) + `ApplicationExitInfo.REASON_ANR` with the system trace (API 30+) |
| Native crash | `native` | `ApplicationExitInfo.REASON_CRASH_NATIVE` tombstone (API 31+) |
| Handled errors | `handled` | `Trel.captureException(t)` |
| Sessions | — | `started` per launch, `errored` on first handled error, `crashed` on next launch after a crash → crash-free rate per release |
| Breadcrumbs | — | Activity lifecycle, foreground/background, low memory, OkHttp requests, `Trel.addBreadcrumb` (last 100, attached to every exception, free) |
| HTTP spans | — | `TrelInterceptor` (OkHttp) or `Trel.startHttpSpan(...)` for other clients |
| Logs | — | `Trel.log(TrelLevel.WARN, "…")` (billed as events) |

## API

```kotlin
Trel.captureException(e, mapOf("order_id" to id))
Trel.captureMessage("checkout skipped", TrelLevel.WARN)
Trel.addBreadcrumb("Tapped Pay", category = "user")
Trel.setUser(id = "u_42", email = "a@b.co")
Trel.setTag("tier", "gold")
Trel.log(TrelLevel.INFO, "cache warmed", mapOf("items" to 120))
Trel.flush()
```

`TrelOptions`: `apiKey`, `environment`, `release` (default `versionName+versionCode`), `service`
(default application id), `endpoint`, `enableAnr`, `anrTimeoutMs`, `enableNetworkBreadcrumbs`,
`breadcrumbsAsLogs`, `maxBreadcrumbs`, `minLogLevel`, `enableScreenSpans`, `debug`, `beforeSend`.

## Delivery

Everything goes to `filesDir/trel/queue/` first, then to `https://ingest.trel.to` (gzip OTLP/JSON)
on init, 2 s after new records, every 30 s in the foreground, on network regain and on background.
5xx / 429 back off exponentially; 402 (plan limit) stops sending for the process. The queue is
capped at 200 files / 8 MB; crash envelopes are never trimmed.

## Obfuscated stacks (R8 / ProGuard)

The AAR ships `-keepattributes SourceFile,LineNumberTable`. Upload `mapping.txt` per release so
Trel retraces class and method names:

```sh
npx @trel-to/cli symbols upload app/build/outputs/mapping/release/mapping.txt \
  --kind proguard --release "1.4.2+87" --key $TREL_KEY
```

`--release` must match the SDK's `release` (default `versionName+versionCode`).

## Publishing (maintainers)

Maven Central via the Sonatype Central Portal using `com.vanniktech.maven.publish`.

```sh
export ORG_GRADLE_PROJECT_mavenCentralUsername=…
export ORG_GRADLE_PROJECT_mavenCentralPassword=…
export ORG_GRADLE_PROJECT_signingInMemoryKey="$(gpg --export-secret-keys --armor <KEY_ID>)"
export ORG_GRADLE_PROJECT_signingInMemoryKeyPassword=…

# bump TREL_SDK_VERSION in gradle.properties, then:
./gradlew publishAndReleaseToMavenCentral --no-configuration-cache
```

Then, from the monorepo root with a clean tree, refresh the public mirror at `github.com/waytodev/trel-android` (also tags `v<version>`): `pnpm mirror:sdks android`.

Local check without credentials: `./gradlew assembleRelease` (AARs in `*/build/outputs/aar`).
