# ASTER Mobile (Android)

Native Kotlin browser using the system Android WebView. Built feature-for-feature alongside ASTER Desktop under the 5.0 parity program.

> **Status: debug-signed preview.** The published APK uses the standard Android debug certificate (development identity, not a publisher release). Android will warn about unknown developers. Verify the SHA-256 in the release notes before installing.

## Installation

1. Download `Aster-mobile-x.y.z-debug.apk` from [Releases](../../releases).
2. Verify SHA-256: `certutil -hashfile <file> SHA256` (Windows) or `sha256sum <file>` (macOS/Linux).
3. Enable "Install unknown apps" for your file manager when Android asks, then install.
4. Requires Android 9.0 (API 28) or newer.

## Features

- 12 independent WebView tabs; HTTPS-first typed navigation; HTTP only when explicitly typed
- Mobile Shield: user-imported threat/tracker/content domain lists (Standard/Enhanced/Strict modes + exceptions) — no bundled threat intelligence
- Download Guard: explicit consent before any transfer, HTTPS-only, 100 MiB cap, max 5 redirects, no cookie/auth propagation, local SHA-256/format checks, never auto-opens files
- **5.0 parity**: Password Manager (Android Keystore AES-256-GCM, strict origin binding), long-press context menus (links/images/pages/tabs), Security Center dashboard, visible-page capture to Pictures/Aster, tab manager with reopen-closed
- Site controls: per-origin JavaScript/downloads (session), third-party cookie toggle
- Remote debugging: `chrome://inspect` works in debug builds only

## Build from source

```
gradle -p . --no-daemon testDebugUnitTest assembleDebug   # debug APK
gradle -p . --no-daemon assembleRelease bundleRelease     # needs signing config
```

Requirements: JDK 17, Android SDK 35 (build-tools 35.0.0). Signing config reads env vars `ASTER_ANDROID_KEYSTORE`, `ASTER_ANDROID_STORE_PASSWORD`, `ASTER_ANDROID_KEY_ALIAS`, `ASTER_ANDROID_KEY_PASSWORD` — never commit a keystore.

CI (`.github/workflows/android-ci.yml`) builds the debug APK and unit tests on every push; the signed release job runs only on version tags **and** only when `ANDROID_KEYSTORE_B64` (base64), `ANDROID_KEYSTORE_PASSWORD`, `ANDROID_KEY_ALIAS`, `ANDROID_KEY_PASSWORD` secrets are configured. No signed release is fabricated without them.

## Permissions

`android.permission.INTERNET` — the only permission requested.

Camera, microphone, and location are denied in this preview and no permission plumbing is implemented.

## Known limitations

- No private browsing: Android WebView storage is process-scoped; the app refuses to fake incognito mode
- No extensions: the system WebView has no extension runtime (documented; Mobile Shield domain lists are the alternative)
- Mobile Shield depends entirely on lists you import — empty lists provide no list-based protection
- Download checks are local format/SHA-256 inspections, **not antivirus**; a matching hash never proves safety
- No device/emulator testing has been performed yet (no device available in the development environment)

## License

MIT — see [LICENSE](./LICENSE).
