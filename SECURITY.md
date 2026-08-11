# Mobile release security

Tagged releases require a stable production keystore through GitHub Secrets:

- `ANDROID_KEYSTORE_BASE64`
- `ANDROID_KEYSTORE_PASSWORD`
- `ANDROID_KEY_ALIAS`
- `ANDROID_KEY_PASSWORD`

CI on branches builds a separate `.debug` application ID. Releases run
`assembleRelease`, `apksigner verify`, tests and Android lint. The updater
checks HTTPS, SHA-256, size and that the downloaded APK has the same signer as
the installed Halla app.

Identity private keys and saved passwords are AES-GCM encrypted with a
non-exportable Android Keystore key. Android cloud/device backup is disabled.
