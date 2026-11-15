# Security Policy for ASTER Mobile (Android)

## Reporting

Use GitHub's private vulnerability reporting on this repository: **Security → Report a vulnerability**. No public security email is configured yet.

## Honest security posture

- Published APKs are **debug-signed** until the owner configures release signing secrets. Verify the SHA-256 in each release before installing.
- The app requests only `android.permission.INTERNET`.
- Password vault: Android Keystore AES-256-GCM with a non-exportable key; sealed blobs stored app-private; nothing transmitted. Web-form autofill capture is not implemented.
- Download Guard performs local format/SHA-256 checks only — **not antivirus**; hashes never establish safety.
- Mobile Shield uses only user-imported domain lists; no threat intelligence feed exists.
- Camera/microphone/location are denied; no permission plumbing exists.

## Release signing (owner action)

Configure repository secrets to enable signed releases (see `.github/workflows/android-ci.yml`):
- `ANDROID_KEYSTORE_B64` — base64 of the keystore
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

Never commit the keystore file or any password. The workflow deletes the decoded keystore from the runner in an `always()` step.
