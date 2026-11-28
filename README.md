# JustVideo

A fast, offline, free video editor for Android phones, built for short-form content.
Its headline feature is **Auto Highlights**: it finds the best 10–15 s moments in a long video.

## Status
Phase 1 (core editor): complete.
Phase 2 (Auto Highlights): complete: audio + speech (whisper.cpp) + visual (motion, scene cuts, ML Kit faces) analysis,
weighted scoring, snapped 10–15 s clips, Highlights screen, settings/presets, batch 9:16 export with captions + auto-reframe.
Phase 3 (editing): complete: undo/redo, text, editable word-highlight captions, transitions (fade, crossfade, slide, zoom),
volume/fades, speed 0.25–4×, crop/zoom/pan, auto-reframe (ML Kit faces), stickers and picture-in-picture.

## Create tab roadmap
These categories are adapted from the awesome-claude-video-skills list, reworked as offline, on-device features:

| Category | Feature | Phase |
|---|---|---|
| Edit a video | Timeline editor | 1 (ready) |
| Shorts & social | Auto Highlights | 2 |
| Explainers | Auto-captions with word-by-word highlight | 3 |
| Stories & slideshows | Ken Burns photo slideshow + transitions | 3 |
| Motion graphics | Animated titles, lower thirds, stickers | 3 |
| Music videos | Beat-synced cuts | 4 |
| Promo & demos | Templates (intro, highlights, end screen) | 4 |
| Tutorials | Step titles + zoom-ins | 4 |

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
