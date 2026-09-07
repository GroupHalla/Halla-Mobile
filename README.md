<p align="center">
  <img src="https://i.imgur.com/eJFWf5w.png" width="110" alt="Halla Mobile" />
</p>

<h1 align="center">Halla Mobile</h1>

<p align="center">
  Native Android client for <a href="https://github.com/GroupHalla/Halla">Halla</a> —
  the TeamSpeak 3-style desktop voice client. Kotlin for the interface,
  C++/JNI for the network and voice core.
</p>

<p align="center">
  <b>com.halla.mobile</b> · Android 8.0+ (API 26)
</p>

---

## Table of Contents

- [Overview](#overview)
- [Key Features](#key-features)
- [Add-ons (plugins)](#add-ons-plugins)
- [WebRTC Viewer (WebView)](#webrtc-viewer-webview)
- [Architecture](#architecture)
- [Native Core (C++/JNI)](#native-core-cjni)
- [Background Service](#background-service)
- [Permissions Used](#permissions-used)
- [Repository Structure](#repository-structure)
- [Building](#building)
- [CI/CD](#cicd)
- [Languages](#languages)
- [Related Projects](#related-projects)
- [Note about the root `CMakeLists.txt`](#note-about-the-root-cmakeliststxt)

---

### Feedback and Bug Reporting Program —  Halla
As the Halla ecosystem (Desktop, Mobile, and Server) continues to advance, our commitment is
to ensure maximum stability, security, and performance in voice and screen transmissions.
So that we can identify and fix any failures quickly, we have opened an official, direct
channel for collecting bug reports, inconsistencies, and technical improvement suggestions.

**What you can report:**
- Connectivity, latency, or server synchronization issues.
- Audio capture or playback failures (noise, echo, or dropouts).
- Screen sharing instabilities (FPS drops, reduced resolution, or freezing).
- Visual and behavioral bugs in the Desktop interface (Windows/Linux) or Mobile (Android).
- Suggestions for new features and usability improvements.

Your contribution is essential to the continuous improvement of this open-source project.
Submit your report through the official form:
https://docs.google.com/forms/d/e/1FAIpQLScwy7k_HyeNnl8kuNfMSs8H-pHUGfhuKijAxkYkzd7m_aX4NA/viewform

We thank everyone for their collaboration in strengthening the platform.

---

## Overview

**Halla Mobile** is the Android client for Halla: it lets you join the same
**Halla Server** servers used by the desktop client, with voice, text chat,
channels, groups/permissions, and whisper — from your pocket, even with the
app in the background (foreground service + floating PTT button over other
apps).

Unlike a "wrapper" app, Halla Mobile **does not run Qt** — it is a native
Android app (Kotlin + Android Views) with its own network/audio core written
in C++, compiled as a native library (`libhalla-core.so`) and accessed via
JNI.

## Key Features

- Connection to Halla Server servers (same protocol as the desktop client).
- Real-time voice: capture/playback via `AudioRecord`/`AudioTrack`, **Opus**
  codec (compiled from the official source), with echo cancellation
  (`AcousticEchoCanceler`) and noise suppression (`NoiseSuppressor`) when
  available on the device.
- Push-to-talk, continuous transmission, or voice detection — including a
  **floating PTT button** (overlay over other apps) so you can talk without
  opening Halla Mobile.
- **Whisper**: specific channels or user lists, with the same concept as the
  desktop client.
- Channels, text chat (with history/scrolling), server/channel groups,
  granular permissions, talk power, poke.
- Full channel administration (parity with the Desktop):
  temporary/semi-permanent/permanent creation with topic, password, codec,
  quality, bitrate, and client limit; editing of all fields; deletion with
  confirmation; moving within the hierarchy; and per-role permissions with
  Allow/Deny/Inherit (view, join, speak, chat, listen, add-ons, files). Every
  action respects the server's permissions.
- Official global badges bound to the UID, fetched from a signed Ed25519
  registry and cached for offline operation.
- Audio routing to headset/speaker/Bluetooth.
- Local recording and audio diagnostics (panel with voice pipeline status,
  useful for debugging microphone/speaker issues).
- Silent reconnection when switching networks (Wi-Fi ↔ mobile data).
- Temporary channel creators get limited local controls: password, bitrate,
  max clients, and kicking users only from that channel.
- Persistent notification with quick actions (mute microphone, mute
  speakers, disconnect) while connected.
- In-app update checking and downloading (SHA-256 checksum verified before
  installing).
- UI with a light/dark theme (`Theme.AppCompat.DayNight`) and RTL support.
  Text input fields created by dialogs use an explicit light background,
  black text, and dark hint color, remaining legible in both themes and on
  OEM skins.
- Localized in **Portuguese, English, and Spanish** (language switching in
  the app, not only through the system).
- **Stream your own screen at up to 30 FPS plus capturable internal audio**
  using Android's official `MediaProjection`/`AudioPlaybackCapture`
  authorization. The internal PCM feeds a real WebRTC `AudioTrack` used
  exclusively by Mobile and Desktop viewers. The track receives continuous
  PCM and disables microphone AEC/AGC/NS to preserve music, games, and
  videos. The libwebrtc AAR is pinned and patched to correctly validate the
  ADM's direct buffer; before streaming, the user chooses among
  720p/1080p/1440p/2160p and 30/60 FPS presets filtered by the maximum
  resolution, FPS, and bitrate advertised by the HallaServer. Resolution
  (480p, 720p, 1080p, 2K, or 4K), FPS, and bitrate are chosen separately;
  the width preserves the aspect ratio configured on the server. The track
  is not suspended on momentary network drops. The Halla UID is excluded so
  that the call's voices do not come back into the live stream. You can also
  watch Desktop or other phone streams; the viewer has its own controls to
  mute the audio and stop watching, without showing the technical
  “Connection: connected” state.
- **Add-ons (plugins)**: `.halla-addon` packages with the same C ABI as the
  Desktop, audio hooks, `plugin_data` v5 transport, and the official
  built-in radio voice add-on (see [Add-ons](#add-ons-plugins)).

**Security**
- Control channel over TLS with TOFU pinning (same scheme as the desktop
  client).
- Ed25519 identity: uses the native `java.security` API (Android 12/API 31+)
  when available, with an updated Bouncy Castle as the compatible fallback
  implementation on older devices (API 26+). The private key is stored
  encrypted with an **Android Keystore** AES key, not in plain text. Because
  Android wipes the Keystore on uninstall, the identity manager exports a
  portable, password-protected backup (PBKDF2-SHA256 + AES-256-GCM); when
  imported after a reinstall, the same key and UID are restored and
  re-wrapped by the new Keystore.
- **Real E2EE (protocol v6)**: voice/chat/poke/offline keys generated and
  distributed by the clients themselves (`E2eeEngine` + `E2eeCrypto`);
  `e2e_key` envelopes with ephemeral X25519 + HKDF-SHA256 + AES-256-GCM;
  X25519↔Ed25519 binding signed at login; private chat/poke/offline
  peer-to-peer (static-static); identity verification via a **9-digit SAS
  code** in the user dialog. The server never sees content keys.
- Voice encrypted with ChaCha20-Poly1305 via **mbedTLS** (the same AEAD
  technique as the desktop client, a different implementation chosen for
  being lighter on Android), using the E2EE group keys — without a live
  key, the frame does not go out.
- **Signed** release pipeline: tag builds require the production keystore
  (GitHub Secrets), and the final APK goes through `apksigner verify`
  before publishing.

## Add-ons (plugins)

The Halla Desktop add-on system now also exists on Mobile, under
**Settings → Add-ons**:

- **Same package format**: `.halla-addon` files (ZIP with `manifest.json`),
  identical to the Desktop's — same manifest, same declared capabilities,
  same packager (`tools/package_plugin.py` from the Halla repository).
- **Same public C ABI**: the native host (`plugin_host.cpp`) implements the
  ABI from `halla_plugin_api.h` (base ABI 1) with the modular interfaces
  `halla.core.v1`, `halla.connection.v1`, `halla.audio.v1`, `halla.data.v1`,
  and `halla.ui.v1` via `query_interface()`. A plugin written for the
  Desktop, recompiled for Android (NDK), works with no code changes —
  libraries are declared in the manifest under `android-arm64`,
  `android-arm`, `android-x86_64`, or `android-x86` and loaded with
  `dlopen()`.
- **Audio pipeline**: PCM processors at the capture stage (before Opus) and
  at the remote voice stage (per sender, after decoding), plus per-user
  gain, distance attenuation, and radio effect.
- **`plugin_data` transport (protocol v5)**: add-ons exchange binary
  payloads (≤ 8 KiB) with instances of the same add-on on other clients,
  over the TLS channel. The server requires `pluginData`, restricts targets
  to the same channel, and reserves global broadcasts for
  `pluginDataGlobal`; Mobile negotiates v5 in the `hello`.
- **Built-in official add-on**: **police radio voice**, the same DSP as the
  Desktop (AGC, narrow radio-communication band, saturation, squelch, and
  static), applied when transmitting and/or listening, with no external
  `.so` needed.
- **Official add-on updates via the catalog**: the same effect is also
  published as a `.halla-addon` package in the
  [Halla-Addons](https://grouphalla.github.io/Halla-Addons/) hub — installed
  via the **online catalog**, the `official.radio-voice` package
  **replaces** the built-in one; once removed, the built-in add-on is
  restored to its previous state. This is how the radio filter is improved
  without publishing a new APK: the Android library source lives in
  `app/src/main/cpp/addons/radio-voice/` and CI publishes the `.so` files
  for the four ABIs as an artifact.
- Features with no Android equivalent (global hotkeys, host-side per-user
  local mute) return `HALLA_RESULT_UNAVAILABLE`, as provided for by the ABI
  specification — plugins must tolerate absences.

> **Security:** as on the Desktop, a native library runs in the same process
> and with the same privileges as the app. Manifest capabilities are
> informational; install only add-ons from trusted sources. Installation
> validates the manifest, limits sizes, and blocks paths outside the
> package (zip-slip).

## WebRTC Viewer (WebView)

To watch someone's screen share (the desktop client's WebRTC mode), Halla
Mobile opens an internal `WebView` and uses the **native `RTCPeerConnection`
from Android's own System WebView**, instead of bundling a native libwebrtc
AAR. This choice was deliberate — some versions of the native Android WebRTC
SDK crashed the app's main process on certain devices; running the
signaling/media inside the WebView's isolated process avoids this problem,
and it still uses a real WebRTC implementation (the Chromium/WebView one),
not a simulation.

## Architecture

```
┌────────────────────────────────────────────┐      JNI       ┌─────────────────────────────┐
│        Kotlin (Android app)                │ ─────────────► │  C++ native (jni_bridge)    │
│                                            │ ◄───────────── │                             │
│  MainActivity    — integration core        │   callbacks    │  HallaClientCore            │
│    (lifecycle, protocol callbacks,         │                │   • raw TCP/UDP sockets     │
│     control dock)                          │                │   • structural JSON parser  │
│  13 controllers  — UI and domain:          │                │     (tracks objects/        │
│    Chat, Identity, RoleIcon, ScreenShare   │                │      strings/escapes)       │
│    Whisper, ServerAdmin, Servers,          │                │   • Opus codec (encode/     │
│    UserDialogs, ChannelTree,               │                │     decode)                 │
│    ChannelDialogs, AudioRoute, Settings,   │                │   • threads: TCP, UDP,      │
│    State (user/channel changes)            │                │     ping, NAT keepalive —   │
│  E2eeEngine      — E2EE v6 keys            │                │     per-thread JNI attach   │
│  HallaService    — foreground service,     │                │      (RAII, one-time)       │
│    notification, floating PTT, reconnection│                │                             │
│  HallaAudioManager — capture/playback      │                │                             │
│    PCM, AEC/NS on communication route      │                │                             │
│  HallaCore       — facade for the external │                │                             │
│    JNI functions                           │                │                             │
│  LocaleManager   — language                │                │                             │
└────────────────────────────────────────────┘                └─────────────────────────────┘
```

- **`HallaCore`** (Kotlin `object`) is the JNI facade: it loads
  `libhalla-core.so` and declares the native functions (`connectToServer`,
  `joinChannel`, `sendChatMessage`, `sendVoiceFrame`, `sendStatus`,
  administration actions such as `sendKick`/`sendBan`, etc.) and the
  `Callbacks` interface that C++ calls back (`onConnected`,
  `onWelcomeReceived`, `onChannelListReceived`, `onUserListReceived`,
  `onChatMessageReceived`, `onAudioFrameReceived`, `onPingUpdated`,
  `onPokeReceived`...).
- **`HallaAudioManager`** handles only the Android side of the audio: it
  opens the `AudioRecord`/`AudioTrack`, enables the native echo/noise
  effects when available, and uses the **communication audio route** when
  AEC is available — giving the hardware echo canceller the right path to
  work in speakerphone mode — and delivers/receives raw PCM blocks; Opus
  encoding/decoding happens on the C++ side.
- **`HallaService`** is a foreground `Service` (type `microphone`) that
  keeps the session alive with the screen off or the app in the background,
  draws the floating PTT button (`WindowManager` + `SYSTEM_ALERT_WINDOW`),
  posts the notification with quick actions, and handles network changes.
- **`MainActivity`** is the main screen, built with traditional Android
  Views (not Compose). After the refactor, it became the **integration
  core** (~1.2 thousand lines): lifecycle, the protocol callbacks, and the
  control dock — the rest lives in **13 cohesive controllers** (Chat,
  Identity, RoleIcon, ScreenShare, Whisper, ServerAdmin, Servers,
  UserDialogs, ChannelTree, ChannelDialogs, AudioRoute, Settings, and
  HallaState), one per UI or business domain, testable on the JVM.
- **`E2eeEngine`/`E2eeCrypto`/`E2eeGroupLogic`** implement the E2EE v6
  engine: group key generation/rotation by the component's master,
  `e2e_key` envelopes, peer-to-peer content, and SAS codes.
- **`LocaleManager`** applies the language chosen in the app, regardless of
  the system language.

## Native Core (C++/JNI)

`app/src/main/cpp/jni_bridge.cpp` implements the `HallaClientCore` class,
self-contained, with no dependency on Qt:

- Control connection over a raw **TCP socket** (BSD sockets/POSIX), with a
  purpose-built **structural JSON parser** (`jsonExtractString`,
  `jsonExtractArray`): it tracks object/array depth, strings, and escapes,
  and supports negative numbers — immune to values that collide with key
  names and to unbalanced brackets inside strings, without bringing a full
  JSON library into the native binary.
- Voice over a **UDP socket**, with a `std::thread` dedicated to the
  receive loop (`udpLoop`) and another for NAT keepalive/hole-punching
  (`udpPingLoop`).
- A dedicated `std::thread` for the TCP control loop (`tcpLoop`) and another
  just for measuring latency (`pingLoop`).
- **Per-thread JNI attach (RAII)**: each native thread calls
  `AttachCurrentThread` exactly once on entry (with automatic detach at the
  end) and keeps the `JNIEnv` cached in thread-local storage — instead of
  paying attach/detach on **every** callback (~20 call sites), reducing
  latency and CPU per audio frame.
- Voice encoding/decoding with **libopus**, and AEAD encryption
  (ChaCha20-Poly1305) with **mbedTLS** — both downloaded and compiled at
  build time via `FetchContent` (see `app/src/main/cpp/CMakeLists.txt`),
  with no need to package precompiled binaries per Android ABI.
- Ed25519 identity generation/signing stays on the Kotlin side
  (`HallaCore.identityPublicKeyBase64`/`signIdentityNonceBase64`, called
  back via JNI) — keeping the key material under the Android Keystore
  instead of passing through the C++ core.
- Every relevant event (connected, disconnected, welcome, channel/user
  list, chat message, received audio frame, error, ping, poke...) becomes
  a JNI call back into Kotlin's `HallaCore.Callbacks`.

## Background Service

Much of the app's value lies in continuing to work with the screen off or
the user in another app — the way a "real" voice client should behave:

- `HallaService` runs as a **foreground service**
  (`foregroundServiceType="microphone"`), with the mandatory notification
  (an Android requirement to keep the microphone active in the
  background).
- A **floating PTT button** can be drawn on top of other apps
  (`SYSTEM_ALERT_WINDOW` permission) to hold and talk without opening
  Halla Mobile.
- It detects network changes (`ConnectivityManager`/`NetworkCapabilities`)
  to reconnect without the user noticing when switching from Wi-Fi to
  mobile data (or vice versa).

## Permissions Used

| Permission | Purpose |
|---|---|
| `INTERNET` | connection to the Halla Server |
| `RECORD_AUDIO` | microphone capture |
| `MODIFY_AUDIO_SETTINGS` | communication audio mode, headset/speaker routing |
| `FOREGROUND_SERVICE` / `FOREGROUND_SERVICE_MICROPHONE` | keeping the call active in the background |
| `POST_NOTIFICATIONS` | active call notification with quick actions |
| `SYSTEM_ALERT_WINDOW` | floating PTT button over other apps |
| `REQUEST_INSTALL_PACKAGES` | installing the update downloaded in the app |
| `BLUETOOTH`, `BLUETOOTH_CONNECT`, `BLUETOOTH_SCAN` | audio routing to Bluetooth headsets |

## Repository Structure

```
app/
├── build.gradle.kts              Android module (SDK, NDK, dependencies)
└── src/main/
    ├── AndroidManifest.xml
    ├── cpp/
    │   ├── CMakeLists.txt         builds libhalla-core.so (fetches Opus via FetchContent)
    │   ├── jni_bridge.cpp         native network/voice core (JNI)
    │   ├── halla_plugin_api.h     public C ABI for add-ons (same as the Desktop)
    │   ├── plugin_host.h/.cpp     add-on host: dlopen, interfaces
    │   │                          halla.core/connection/audio/data/ui v1,
    │   │                          PCM hooks and plugin_data transport (v5)
    ├── kotlin/com/halla/mobile/
    │   ├── HallaCore.kt           JNI facade (external functions + callbacks,
    │   │                          + Ed25519 identity generation/signing)
    │   ├── MainActivity.kt        integration core: lifecycle,
    │   │                          protocol callbacks, control dock
    │   ├── ChatController.kt .. HallaStateController.kt
    │   │                          13 cohesive controllers extracted from the old
    │   │                          monolith (Chat, Identity, RoleIcon,
    │   │                          ScreenShare, Whisper, ServerAdmin, Servers,
    │   │                          UserDialogs, ChannelTree, ChannelDialogs,
    │   │                          AudioRoute, Settings, State)
    │   ├── E2eeEngine.kt / E2eeCrypto.kt / E2eeGroupLogic.kt
    │   │                          E2EE v6 engine: group keys, e2e_key
    │   │                          envelopes, peer-to-peer, SAS
    │   ├── HallaAudioManager.kt   PCM capture/playback, AEC/NS on the
    │   │                          communication route, local recording
    │   ├── HallaService.kt        foreground service, notification, PTT overlay
    │   ├── HallaWebRtcViewer.kt / HallaWebRtcBroadcaster.kt
    │   │                          stream/watch screen (WebRTC)
    │   ├── LocaleManager.kt       runtime language switching
    │   ├── PluginManager.kt       add-ons: .halla-addon packages, manifest,
    │   │                          enable/disable, schema-based settings
    │   └── …                      (AddonCatalog, BadgeRegistry, RoleIconCache,
    │                              HallaUidPersistence, HallaUpdateManager …)
    └── res/
        ├── drawable/              vector icons and logo
        ├── layout/activity_main.xml
        └── values(-en|-es)/strings.xml   pt-BR (default), English, Spanish
build.gradle.kts, settings.gradle.kts, gradle.properties, gradlew
```

## Building

**Requirements**: Android Studio (or the `gradlew` from the command line)
with JDK 17, Android SDK 34, and NDK **25.2.9519653** installed.

```bash
./gradlew assembleDebug   # development; applicationId with .debug suffix
```

Official releases use `assembleRelease`, a stable keystore configured
through GitHub Secrets documented in `SECURITY.md`, `apksigner verify`,
SHA-256, unit tests, and Android lint.

Gradle, via `externalNativeBuild`, triggers CMake from
`app/src/main/cpp/CMakeLists.txt` automatically — there is no need to run
CMake manually. On the first build, CMake downloads and compiles Opus from
source (internet access required at this step).

## CI/CD

`.github/workflows/android.yml` runs tests and lint on pushes/PRs, builds a
separate debug APK just for CI and, on `v*` tags, requires the production
keystore, produces a signed `HallaMobile.apk`, validates the signature, and
publishes the APK + SHA-256.

## Languages

`values/` (default, Portuguese), `values-en/`, and `values-es/` — about 385
translated strings in each. The language can be switched inside the app
itself (`LocaleManager`), regardless of the Android system language.

## Related Projects

- **[Halla](https://github.com/GroupHalla/Halla)** — the desktop client
  (Windows/Linux, Qt 6) that speaks the same protocol. Desktop and Mobile
  can share screens via WebRTC; Mobile uses MediaProjection to publish and
  WebView/Chromium to watch.
- **[Halla Server](https://github.com/GroupHalla/HallaServer)** —
  self-hostable server; see
  [`PROTOCOL.md`](https://github.com/GroupHalla/HallaServer/blob/main/PROTOCOL.md)
  for the full protocol specification (currently v6, with E2EE).

## Note about the root `CMakeLists.txt`

The repository still has a `CMakeLists.txt` at the root that references a
Qt Quick/QML app (`HallaMobileApp`, `src/main.cpp`, `src/Main.qml`,
`src/net/MobileNetSession.*`). Those files **do not exist** in the project
tree — it was an initial approach (Qt for Android), replaced by the native
Android app described above. The real build, local and in CI, uses Gradle
exclusively (`./gradlew assembleDebug`/`assembleRelease`); that root
`CMakeLists.txt` remains dead code and can be safely removed.

## License

Free for non-commercial use ([`LICENSE`](LICENSE)): use, study, modify, and
redistribute free of charge, without asking permission. Selling, renting,
or embedding it in a commercial product requires written authorization from
the maintainers. Third-party components (Opus, mbedTLS, WebRTC Android
SDK) follow their respective original licenses.
