# NetLog

An Android HTTP collector with a desktop inspector. Open NetLog, run an instrumented debug app on an emulator or USB device, and requests appear automatically. No proxy, certificates, IP address, Android Studio attachment, or cloud account.

## Open the desktop app

On this checkout, the packaged macOS app is generated at `desktop/build/app/NetLog.app`. Double-click it. It includes Java and Android platform-tools; users do not need a Java installation or to configure ADB. USB phones still require Android's normal USB debugging authorization. A running emulator needs no additional setup.

The app automatically discovers every connected device and every running app with NetLog. Use the app/device filter when more than one is active. Disconnections and process restarts reconnect automatically; previous desktop rows remain until cleared or evicted.

The live status names only devices with a working capture session. Retained rows from an earlier session do not mean that device is still connected.

## Integrate once per Android project

Publish both artifacts locally from this directory:

```sh
./gradlew :netlog:publishReleasePublicationToMavenLocal :netlog-no-op:publishReleasePublicationToMavenLocal
```

Add `mavenLocal()` to your dependency repositories, then:

```kotlin
debugImplementation("io.github.kevinmf1:netlog:0.1.0-SNAPSHOT")
releaseImplementation("io.github.kevinmf1:netlog-no-op:0.1.0-SNAPSHOT")

// Add last, after interceptors that modify authentication headers or request bodies.
builder.addInterceptor(NetLogInterceptor(context))
```

The Android library has no activity, notifications, extra permissions, TLS overrides, or JVMTI agent. The no-op artifact has only the interceptor API and forwards calls unchanged. The capture artifact also refuses to activate in a non-debuggable app. Use the no-op dependency for every production build type, including custom build types.

The artifacts are local snapshots, not published to a remote Maven repository. Integrate the interceptor into each OkHttp client you want to inspect. Clients created internally by other SDKs are not automatically captured.

## Build and test

JDK 17+, Android SDK 36, and SDK platform-tools are required for development. The app package includes its own runtime for end users.

```sh
./gradlew :netlog:testDebugUnitTest :desktop:test :sample:assembleDebug
./gradlew :desktop:packageMac
```

`packageMac` packages for the host Mac's architecture. It refuses to replace a running NetLog, builds in a staging folder, and verifies the complete bundle's code signature before replacing the previous package. Builds carry a local ad-hoc signature; distributing outside this machine requires Developer ID signing and Apple notarization. No signing keys are needed for local development. The Java desktop code can run on other operating systems with `:desktop:run`, but only the macOS app package is provided here.

For an independent emulator/device test, install `sample/build/outputs/apk/debug/sample-debug.apk` and open **NetLog sample**. It makes four labeled requests to a server inside the sample process (200, 401, 503, and POST). No external services or credentials. The button sends another batch. The desktop's `--probe` argument prints discovery status and captured rows for transport diagnostics.

## Capture behavior

- Only OkHttp clients with the interceptor are captured; Retrofit using those clients is covered. This is app-level inspection, not packet capture or a system-wide proxy.
- Application interceptors show logical calls; automatic redirects/retries are not separate rows. The final URL is displayed. Place NetLog after authentication interceptors to see their changes.
- Request bodies are copied as OkHttp sends them, never written a second time. One-shot behavior is preserved; duplex request bodies are omitted.
- Response bodies are copied as the application consumes them. An unconsumed body stays in `Reading body`; closing early is explicitly marked. Streaming responses are not drained or delayed to populate the inspector. The body is finalized on EOF, declared length, or close; continuous live stream previews are not supported.
- Text/JSON/XML/form bodies are limited to 64 KiB per direction. Binary, encoded, and unknown content types are omitted. Bodies shown are application-level decoded data when OkHttp transparently decompresses them.
- Timing distinguishes response headers from elapsed time through body completion/close. Response byte count is bytes consumed by the app, not wire size.
- Android retains at most 200 rows and two million serialized characters; desktop at most 1,000 rows and eight million serialized characters. Both are memory-only. Large bodies can cause earlier eviction. Capture has bounded memory/copying overhead; release uses the no-op artifact.
- Pause freezes the view while capture continues. Clear removes retained desktop rows and ignores further updates to those same requests; new requests still appear. Device-side retained rows can reappear after reopening the desktop app.
- Authorization, Proxy-Authorization, Cookie, Set-Cookie, and X-Api-Key headers are redacted before transfer. Additional header names can be passed after `context` in the interceptor constructor. URL credentials and common sensitive query parameters are redacted. Body fields and arbitrary query/header names are **not** automatically sanitized; use test accounts and avoid sharing sensitive payloads. Copied cURL uses redacted values and may need credentials/body replacement before replay.

## Transport

The debug library binds an Android abstract local socket named `netlog.v1.<package>.<pid>`. The desktop enumerates ADB devices, discovers those sockets through `/proc/net/unix`, and allocates a loopback ADB forward with `tcp:0`. This same route works for emulators and USB devices, with no emulator-specific IP or network security configuration. The server permits only shell/root or its own UID and does not listen on Wi-Fi. The desktop removes only the forwards it created, without stopping the shared ADB server.

Protocol v1 is newline-delimited JSON. Client sends its numeric revision cursor and a newline. Server sends a `hello` with package/session identity, changed retained records, and an `end` cursor. Body completion updates the same request ID. Session UUIDs separate app restarts. Timeouts, size limits, and bounded stores protect the capture path. Some vendor devices may restrict shell access to `/proc/net/unix`; these need device testing and cannot be assumed compatible merely because an emulator passed.
