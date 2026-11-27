# JustVideo

A fast, offline, free video editor for Android phones, built for short-form content.
Its headline feature is **Auto Highlights**: it finds the best 10–15 s moments in a long video.

## Status
Phase 1 (core editor): steps 1–3 done (app skeleton, projects database, importing media).

## Building (cloud build machine)
```
./gradlew testDebugUnitTest assembleRelease
```
The installable APK ends up in `app/build/outputs/apk/release/app-release.apk`.

## Layout
- `app/src/main/java/com/justvideo/app/`
  - `core/`: small helpers (time formatting, in-app log, crash reporter)
  - `data/`: models, Room database, repositories
  - `editor/`: pure timeline editing logic (unit-tested)
  - `ui/`: Compose screens (home, editor, theme, navigation)
- `app/src/test/`: unit tests + Robolectric UI smoke tests
- `signing/justvideo.jks.enc`: the signing key, AES-256 encrypted. GitHub Actions unlocks it with the `JV_KEYSTORE_PASSWORD` secret.
- `.github/workflows/release.yml`: builds, tests and publishes each new versionCode to Releases (the in-app updater reads it).
