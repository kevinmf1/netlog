# NetLog technical details

[Back to the quick start](../README.md)

Use this page for manual setup, capture limits, and transport details. For the normal plugin setup, follow the README.

## Plugin compatibility and legacy setup

With the legacy `apply plugin:` syntax, add `mavenLocal()` to the root `buildscript` repositories (optionally limited with `content { includeGroup 'io.github.kevinmf1' }`), add `classpath "io.github.kevinmf1:netlog-gradle-plugin:0.1.0-SNAPSHOT"`, and use `apply plugin: "io.github.kevinmf1.netlog"`.

Rebuild the debug app. No code changes are needed. For every debuggable variant, the plugin adds the runtime and rewrites the `okhttp3.OkHttpClient(Builder)` constructor in the app and all of its dependencies, so every client gets the capture interceptor, including SDK-owned clients, `new OkHttpClient()`, and `newBuilder().build()` copies. It is added last, after interceptors that already exist on the builder. Non-debuggable variants are not touched: no runtime, provider, or bytecode change.

Manual `NetLogInterceptor` calls and the `netlog`/`netlog-no-op` dependencies are no longer needed with the plugin; if a builder already has a `NetLogInterceptor`, it is not given a second. Do not keep an older collector with a different Maven group in the same app; both bind the same socket name.

Requirements: the app's `minSdk` must be 24 or higher, and it must use OkHttp 4.x or 5.x (`okhttp3` package). OkHttp 2.x (`com.squareup.okhttp`) is not captured; 3.x is untested. The runtime depends on OkHttp 4.12.0, so an app on an older 4.x resolves 4.12.0 in debug only; declare `okhttp:4.12.0` or newer explicitly to keep debug and release on the same version.

Tested with AGP 8.9.1: OkHttp 4.12.0 and 5.3.2 constructor rewrites (unit tests on the real jars), the sample with OkHttp 4.12.0 on an emulator, and an app with OkHttp 5.3.2 (`okhttp-android`) on an emulator, where an SDK-owned client that was previously invisible was captured. Not covered: WebView, Cronet, `HttpURLConnection`, native sockets, shaded/relocated OkHttp, secondary app processes, and requests sent before the app's content providers start. The artifacts are local snapshots, not published to a remote repository.

## Manual interceptor

Without the plugin, add the interceptor yourself:

```kotlin
debugImplementation("io.github.kevinmf1:netlog:0.1.0-SNAPSHOT")
releaseImplementation("io.github.kevinmf1:netlog-no-op:0.1.0-SNAPSHOT")

// Add last, after interceptors that modify authentication headers or request bodies.
builder.addInterceptor(NetLogInterceptor(context))
```

Publish the no-op artifact too with `:netlog-no-op:publishReleasePublicationToMavenLocal`. Only clients you configure are captured.

The Android library has no activity, notifications, extra permissions, TLS overrides, or JVMTI agent. The no-op artifact has only the interceptor API and forwards calls unchanged. The capture artifact also refuses to activate in a non-debuggable app.

## Build and test

JDK 17+, Android SDK 36, and SDK platform-tools are required for development. The app package includes its own runtime for end users.

```sh
./gradlew :netlog:testDebugUnitTest :desktop:test :sample:assembleDebug
./gradlew -p netlog-gradle-plugin test
./gradlew :desktop:packageMac
```

`packageMac` packages for the host Mac's architecture. It refuses to replace a running NetLog, builds in a staging folder, and verifies the complete bundle's code signature before replacing the previous package. Builds carry a local ad-hoc signature; distributing outside this machine requires Developer ID signing and Apple notarization. No signing keys are needed for local development. The Java desktop code can run on other operating systems with `:desktop:run`, but only the macOS app package is provided here.

For an independent emulator/device test, install `sample/build/outputs/apk/debug/sample-debug.apk` and open **NetLog sample**. The sample applies the plugin and has no manual interceptor; inside this repository the plugin uses the `:netlog` project directly, so no Maven Local publish is needed. It makes four labeled requests to a server inside the sample process (200, 401, 503, and POST). No external services or credentials. The button sends another batch. The desktop's `--probe` argument prints discovery status and captured rows for transport diagnostics.

## Capture behavior

- Only OkHttp clients are captured (all of them with the plugin, otherwise those given the interceptor); Retrofit using those clients is covered. This is app-level inspection, not packet capture or a system-wide proxy.
- Application interceptors show logical calls; automatic redirects/retries are not separate rows. The final URL is displayed. Place NetLog after authentication interceptors to see their changes.
- Request bodies are copied as OkHttp sends them, never written a second time. One-shot behavior is preserved; duplex request bodies are omitted.
- Response bodies are copied as the application consumes them. An unconsumed body stays in `Reading body`; closing early is explicitly marked. Streaming responses are not drained or delayed to populate the inspector. The body is finalized on EOF, declared length, or close; continuous live stream previews are not supported.
- Text/JSON/XML/form bodies are limited to 64 KiB per direction. Binary, encoded, and unknown content types are omitted. Bodies shown are application-level decoded data when OkHttp transparently decompresses them.
- Image responses (successful GETs with an `image/*` type or image file extension) get a preview in the Response view. The image is not captured on the device: the desktop downloads the URL itself, only after Response is opened, replaying the app's own request headers (including its token) and keeping the last 20 previews in memory. Device-local addresses are not fetched. PNG, JPEG, GIF, and BMP are shown; WebP and SVG are not.
- Timing distinguishes response headers from elapsed time through body completion/close. Response byte count is bytes consumed by the app, not wire size.
- Android retains at most 200 rows and two million serialized characters; desktop at most 1,000 rows and eight million serialized characters. Both are memory-only. Large bodies can cause earlier eviction. Capture has bounded memory/copying overhead; release builds contain no capture code (plugin) or only the no-op artifact (manual setup).
- Pause freezes the view while capture continues. Clear removes retained desktop rows and ignores further updates to those same requests; new requests still appear. Device-side retained rows can reappear after reopening the desktop app.
- The device sends header and query values as the app used them. The desktop hides Authorization, Proxy-Authorization, Cookie, Set-Cookie, X-Api-Key, and Api-Key headers, URL credentials, and common token/secret/password/API-key query parameters as `[REDACTED]`, in the view and in copies. **Show sensitive values** reveals them for the current desktop session, including in Copy detail and Copy cURL; the desktop always starts hidden. Header names passed after `context` in the manual interceptor constructor are redacted on the device and never sent. Body fields are **not** hidden; use test accounts and avoid sharing sensitive payloads.

## Transport

The debug library binds an Android abstract local socket named `netlog.v1.<package>.<pid>`. The desktop enumerates ADB devices, discovers those sockets through `/proc/net/unix`, and allocates a loopback ADB forward with `tcp:0`. This same route works for emulators and USB devices, with no emulator-specific IP or network security configuration. The server permits only shell/root or its own UID and does not listen on Wi-Fi. The desktop removes only the forwards it created, without stopping the shared ADB server.

Protocol v1 is newline-delimited JSON. Client sends its numeric revision cursor and a newline. Server sends a `hello` with package/session identity, changed retained records, and an `end` cursor. Body completion updates the same request ID. Session UUIDs separate app restarts. Timeouts, size limits, and bounded stores protect the capture path. Some vendor devices may restrict shell access to `/proc/net/unix`; these need device testing and cannot be assumed compatible merely because an emulator passed.

