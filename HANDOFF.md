# OneGallery — Engineering Handoff

**Last verified:** 2026-09-18
**Status:** Builds clean. Not yet run on a physical device.
**Owner:** Victor Ortega

This document is the single source of truth for anyone — human or AI agent — picking up
this codebase. Read it before making changes. Everything marked **VERIFIED** was confirmed
by actually running it on the owner's machine, not inferred from reading code.

---

## 1. Current state

| Item | Status |
| --- | --- |
| `gradlew assembleDebug` | **VERIFIED PASSING** — produces a ~21 MB `app-debug.apk` |
| `gradlew assembleRelease` | **VERIFIED PASSING** |
| Compile warnings | 1 (a deprecated icon, see §6.10) |
| Run on emulator | **NOT YET DONE** |
| Run on physical Pixel | **NOT YET DONE** |
| Automated tests | **NONE EXIST** |

The project previously did **not** compile. One Kotlin error and two missing build files
were fixed on 2026-09-18 — see §7 for the changelog.

---

## 2. Environment (verified on the owner's machine)

| Component | Version / Path |
| --- | --- |
| Gradle | 8.9 (via committed wrapper — works out of the box) |
| Android Gradle Plugin | 8.5.2 |
| Kotlin | 2.0.20 |
| Compose BOM | 2024.09.01 |
| JDK | Android Studio bundled JBR — `C:\Program Files\Android\Android Studio\jbr` |
| Android SDK | `C:\Users\V\AppData\Local\Android\Sdk` |
| Installed platforms | android-35, android-36, android-36.1 |
| `compileSdk` / `targetSdk` | 35 |
| `minSdk` | 26 (but see §6.8 — effectively 29) |
| Test AVD | `Pixel_9_Pro_XL`, system image `android-37` |

### First-time setup

1. Open Android Studio → **File → Open** → select this folder (the one containing
   `settings.gradle.kts`). Do **not** use *Import Project*.
2. Studio generates `local.properties` with your SDK path on first sync. It is gitignored —
   never commit it.
3. Sync. Gradle 8.9 downloads on first run (~130 MB).

### Command line

```bash
gradlew.bat assembleDebug
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

> **Note:** AGP 8.5.2 warns that `compileSdk = 35` is newer than it officially supports. The
> build succeeds anyway. Silence it with `android.suppressUnsupportedCompileSdk=35` in
> `gradle.properties`, or upgrade AGP — do not downgrade `compileSdk`.

---

## 3. What this app is

A Samsung One UI Gallery clone for Pixel/AOSP. Two features are the reason the project
exists; everything else is scaffolding around them.

1. **FilmStrip Infinity Scrubber** — a center-pinned horizontal thumbnail strip with a
   fisheye magnification effect, bidirectionally synced 1:1 with the fullscreen pager.
2. **1-Tap Video Frame Capture** — grab a still from a playing video without entering an
   edit mode, saved to MediaStore in the background.

`CLAUDE.md` holds the reverse-engineering notes on how Samsung implements these.

---

## 4. Architecture

Single-module app, MVI-ish, no DI framework, no ViewModels yet — state lives in composables
and is passed down.

```
app/src/main/java/com/onegallery/app/
├── MainActivity.kt              # Permission gate + top-level grid/viewer switch
├── data/MediaStoreRepository.kt # ContentResolver queries, ContentObserver, snapshot saver
├── domain/MediaItem.kt          # MediaItem, MediaType, Album, DateGroupedMedia
└── ui/
    ├── grid/GalleryGridScreen.kt             # Pinch-zoom grid, bottom nav
    ├── viewer/MediaViewerScreen.kt           # HorizontalPager + zoomable image + overlays
    ├── viewer/MediaDetailsSheet.kt           # ModalBottomSheet with EXIF-ish details
    ├── filmstrip/FilmStripInfinityViewer.kt  # ACTIVE — Compose implementation
    ├── filmstrip/FilmStripLayoutManager.kt   # DEAD CODE — RecyclerView port, unreferenced
    ├── video/VideoPlayerView.kt              # Media3 PlayerView + capture shutter UI
    ├── video/VideoSnapshotManager.kt         # Dual-path frame extraction
    └── theme/{Color,Theme}.kt                # One UI palettes
```

### Data flow

`MediaStoreRepository.observeMediaItems()` returns a `callbackFlow<List<MediaItem>>` backed
by a `ContentObserver`. `MainActivity.GalleryApp` collects it and hands the list to either
`GalleryGridScreen` or `MediaViewerScreen`. There is no caching layer and no pagination —
the entire media library is queried into memory on every change.

### Intentional design notes

- **No `res/` resources.** The manifest deliberately uses framework resources
  (`@android:drawable/ic_menu_gallery`, `@android:style/Theme.Material.NoActionBar`) so the
  project needs no `res/values/themes.xml`. `res/drawable/` and `res/values/` exist but are
  empty. If you add resources that's fine — just don't assume they were forgotten.
- **`viewBinding = true`** is enabled in `app/build.gradle.kts` but unused. Harmless.

---

## 5. Testing checklist

The emulator starts with an **empty** gallery, so the app will correctly show "0 items" and
look broken until you add media:

```bash
adb push C:\Users\V\Pictures\sample.jpg /sdcard/Pictures/
```

```bash
adb shell am broadcast -a android.intent.action.MEDIA_SCANNER_SCAN_FILE -d file:///sdcard/Pictures
```

When the permission dialog appears, **tap "Allow all"** — "Select photos…" currently
dead-ends the app (§6.1).

Manual smoke test, in order:

1. Grid renders thumbnails; the count in the header is correct.
2. Tap a photo → fullscreen viewer opens on the right image.
3. Swipe the pager → filmstrip tracks and stays centered.
4. Scrub the filmstrip → pager follows.
5. Tap image → overlays toggle. Double-tap → zoom.
6. Open a video → it plays, "Capture" button appears.
7. Tap Capture → flash, thumbnail badge, new file in `Pictures/OneGallery_Captures`.
8. Info button → details sheet shows correct resolution / size / path.

---

## 6. Known issues

Ranked by how much they will affect a first test run. Each is traced to specific lines.
**None of these block the build.**

### 6.1 Permission dead-end on Android 14/15 — HIGH, affects Pixel testing

`MainActivity.kt:38-42, 63-86`, `AndroidManifest.xml:7`

The manifest declares `READ_MEDIA_VISUAL_USER_SELECTED`, which makes Android 14+ show a
"Select photos…" option. But `checkAndRequestPermissions()` never requests that permission,
and the result gate is `permissions.values.any { it }`. Choose partial access and every
requested permission comes back denied → permanent permission screen.
`PermissionRequestScreen` also ignores its `onRequest` parameter, so there is **no retry
button** — the only escape is force-stop.

*Fix:* add `READ_MEDIA_VISUAL_USER_SELECTED` to the requested array on API 34+, treat
partial access as a valid granted state, and wire `onRequest` to a button.
*Workaround for now:* tap "Allow all".

### 6.2 Full MediaStore query runs on the main thread — HIGH

`MediaStoreRepository.kt:36-61`

`ContentObserver` is constructed with `Handler(Looper.getMainLooper())`, so every `onChange`
runs `trySend(queryMediaItems())` — the entire cursor loop — on the main thread.
`flowOn(Dispatchers.IO)` does **not** cover this; it only governs the `callbackFlow` builder
block (the initial emission). Expect visible jank or an ANR on a real library.

*Fix:* give the observer a background `Handler` (via `HandlerThread`), or have `onChange`
only signal and let a coroutine in the flow's scope perform the query.

### 6.3 The "instant TextureView" capture path never fires — HIGH (feature degraded)

`VideoPlayerView.kt:161-179, 361-371`

**VERIFIED by decompiling `media3-ui-1.4.1`:** `PlayerView` reads `surface_type` from XML
attributes and defaults to `SURFACE_TYPE_SURFACE_VIEW`. Constructed programmatically as
`PlayerView(ctx)` with no attrs it creates a **SurfaceView**, so `findTextureView()` always
returns `null`. (A SurfaceView's contents can't be read via `getBitmap()` anyway.)

Capture still works, but always through the slower `MediaMetadataRetriever` fallback in
`VideoSnapshotManager.extractHighResFrame()`. The "dual-engine" design described in
`CLAUDE.md` is currently single-engine.

*Fix:* inflate `PlayerView` from a layout XML with `app:surface_type="texture_view"`, or drop
`PlayerView` and attach your own `TextureView` via `player.setVideoTextureView(...)`.

### 6.4 Tabs and action buttons are non-functional — MEDIUM

`GalleryGridScreen.kt:78-110`, `MediaViewerScreen.kt:169, 213-240`

Albums and Search update `selectedTab` but the content never changes — the grid always
renders Pictures. Share, Edit, Favorite, Delete and More are empty lambdas. `isFavorite` is
hardcoded `false` and never read from `MediaStore.IS_FAVORITE`.

### 6.5 Every video page builds its own autoplaying ExoPlayer — MEDIUM

`VideoPlayerView.kt:107-125`

`playWhenReady = true` and `REPEAT_MODE_ONE` per page, with no pause when the page scrolls
off-center. Swiping between two videos can overlap audio. Consider a single shared player
driven by `pagerState.currentPage`.

### 6.6 Filmstrip draws two overlapping frames — LOW (cosmetic)

`FilmStripInfinityViewer.kt:161-166, 203-212`

The always-visible center bracket sits on top of the selected item's own white border.

### 6.7 Pinch-to-zoom on the grid rarely triggers — LOW

`GalleryGridScreen.kt:133-141`

`detectTransformGestures` reports *per-event* zoom deltas (each ≈ 1.0), so the `> 1.25f` /
`< 0.8f` thresholds almost never fire. Accumulate the factor across the gesture instead.

### 6.8 `minSdk = 26` but the query needs API 29 — LOW (irrelevant on Pixel)

`MediaStoreRepository.kt:66-81`

**VERIFIED against `api-versions.xml`:** on `MediaStore.MediaColumns`, the fields
`BUCKET_ID`, `BUCKET_DISPLAY_NAME`, `DATE_TAKEN`, `DURATION` and `ORIENTATION` are all
`since="29"` (only `WIDTH` / `HEIGHT` are older). These are compile-time String constants, so
they inline silently and lint does **not** flag them — but the query may throw on Android
8/9. Either raise `minSdk` to 29 or branch the projection.

### 6.9 Grid is hardcoded dark — LOW

`GalleryGridScreen.kt:72` and throughout. `Theme.kt` defines a complete light color scheme,
but the grid hardcodes `DarkBackground` and `DarkText*`. Light mode looks half-finished.

### 6.10 Deprecated icon — TRIVIAL (the only compile warning)

`MediaViewerScreen.kt:135` — use `Icons.AutoMirrored.Rounded.ArrowBack`.

### 6.11 Dead code

`FilmStripLayoutManager.kt` — a complete RecyclerView `LayoutManager` + `SnapHelper` port
that nothing references. The Compose `FilmStripInfinityViewer` is what actually runs. Keep it
as a reference or delete it, but don't "fix" it thinking it's live.

### 6.12 Viewer can crash if the library shrinks while open — LOW

`MediaViewerScreen.kt:85` indexes `mediaItems[pagerState.currentPage]`. If the
`ContentObserver` fires with a shorter list while the viewer is open, this can throw.

---

## 7. Changelog

### 2026-09-18 — made the project buildable

1. **Fixed compile error** at `VideoPlayerView.kt:313`. Was
   `.padding(horizontal = 24.dp, bottom = 20.dp)` — Compose has no such overload;
   `horizontal`/`vertical` and `start`/`top`/`end`/`bottom` are different, non-mixable
   overloads. Now `.padding(start = 24.dp, end = 24.dp, bottom = 20.dp)`.
2. **Added the Gradle wrapper** (`gradlew`, `gradlew.bat`,
   `gradle/wrapper/gradle-wrapper.jar`). Only `gradle-wrapper.properties` existed, so the
   documented `./gradlew assembleDebug` could never have run.
3. **Added `app/proguard-rules.pro`**, referenced by `app/build.gradle.kts:29` but missing.
4. **Added `.gitignore`.**
5. **Relocated** from `H:\Android\gallery_clone` (a mapped network share) to local disk.
   Gradle 8.9 does build on the network drive, but it is slower and **Gradle 9 rejects
   network-drive project roots outright** — so this move is also future-proofing.

---

## 8. Suggested work queue

Roughly dependency-ordered. Good first tasks are marked ★.

1. ★ Run it on the `Pixel_9_Pro_XL` AVD and record what actually happens (§5).
2. ★ Fix the permission dead-end (§6.1) — highest user-facing risk.
3. Move the `ContentObserver` query off the main thread (§6.2).
4. Restore the real TextureView capture path (§6.3).
5. Introduce a `ViewModel` so media state survives rotation and the repository isn't
   reconstructed inside `VideoPlayerView` (`VideoPlayerView.kt:92`).
6. Implement Share and Delete via `MediaStore` + `IntentSender` (§6.4).
7. Wire the Albums tab to the already-written `queryAlbums()`, and date headers to
   `queryGroupedByDate()` — both exist in the repository but nothing calls them.
8. Add a shared ExoPlayer across pager pages (§6.5).
9. Light theme pass (§6.9).
10. First tests: `MediaItem.formattedDuration`, `formatFileSize`, and the date-grouping logic
    are pure functions and trivially unit-testable. There is currently no test source set.

---

## 9. Conventions for AI coding agents

- **Verify, don't assume.** Run `gradlew.bat assembleDebug` after any change. This codebase
  previously shipped a state that looked correct and did not compile.
- **Don't trust the docs over the code.** `CLAUDE.md` and `README.md` describe intended
  Samsung behavior, some of it aspirational (see §6.3). Code is truth.
- **Two dead ends to avoid:** `FilmStripLayoutManager.kt` is unused (§6.11), and `res/` is
  intentionally empty (§4).
- **Never commit** `local.properties`, keystores, or `app/build/`.
- **Don't bump `compileSdk` down** to silence the AGP warning; suppress it or upgrade AGP.
- **State the scope of your testing** in PR descriptions — say whether you only compiled, or
  actually ran it on a device/emulator.
- **Update §7 and §1** when you change build status or fix a listed issue.
