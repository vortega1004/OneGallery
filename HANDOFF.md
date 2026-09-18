# OneGallery — Engineering Handoff

**Last verified:** 2026-09-18
**Status:** The committed baseline (`fbcc4f4`) builds clean and runs on the emulator (grid
verified). The 2026-09-18 review-fix pass (§7) sits on top of it and has **not been compiled or
run yet**. Not yet run on a physical device.
**Owner:** Victor Ortega

This document is the single source of truth for anyone — human or AI agent — picking up
this codebase. Read it before making changes. Everything marked **VERIFIED** was confirmed
by actually running it on the owner's machine, not inferred from reading code.

---

## 1. Current state

| Item | Status |
| --- | --- |
| `gradlew assembleDebug` | **NEEDS RE-VERIFY** — VERIFIED PASSING on the baseline (~21 MB `app-debug.apk`); the review-fix pass (§7) was written on a machine with no JDK / Android SDK and has not been compiled |
| `gradlew assembleRelease` | **NEEDS RE-VERIFY** (same reason; passed on the baseline) |
| Compile warnings | Unknown until rebuilt (baseline had 1, the deprecated icon, since fixed) |
| Run on emulator | **VERIFIED on the baseline only** — grid loads and renders thumbnails on `Pixel_9_Pro_XL` / API 37. Not re-run since the review-fix pass |
| Run on physical Pixel | **NOT YET DONE** |
| Viewer / filmstrip / video capture | **NOT YET EXERCISED** — only the grid has been confirmed |
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
| `minSdk` | 29 |
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

Single-module app, MVI-ish, no DI framework. `GalleryViewModel` owns the media list; the rest
of the UI state lives in composables and is passed down.

```
app/src/main/java/com/onegallery/app/
├── MainActivity.kt              # Permission gate, ACTION_VIEW handling, grid/viewer switch
├── GalleryViewModel.kt          # StateFlow of the media list (single MediaStore subscription)
├── data/MediaStoreRepository.kt # ContentResolver queries, ContentObserver, snapshot saver
├── domain/MediaItem.kt          # MediaItem, MediaType, Album, DateGroupedMedia
└── ui/
    ├── grid/GalleryGridScreen.kt             # Pinch-zoom grid, bottom nav
    ├── viewer/MediaViewerScreen.kt           # HorizontalPager + zoomable image + overlays
    ├── viewer/MediaDetailsSheet.kt           # ModalBottomSheet with EXIF-ish details
    ├── filmstrip/FilmStripInfinityViewer.kt  # Compose filmstrip, 1:1 synced with the pager
    ├── video/VideoPlayerView.kt              # Media3 PlayerView + capture shutter UI
    ├── video/VideoSnapshotManager.kt         # Dual-path frame extraction
    └── theme/{Color,Theme}.kt                # One UI palettes
```

### Data flow

`MediaStoreRepository.observeMediaItems()` is a `callbackFlow` whose `ContentObserver` only
signals; the signals are conflated, rate-limited (300 ms) and mapped to a full query on
`Dispatchers.IO`, then sorted in Kotlin by resolved date. `GalleryViewModel` exposes it as a
`StateFlow` (`stateIn`, `WhileSubscribed(5s)`), which `GalleryApp` collects with
`collectAsStateWithLifecycle` and hands to `GalleryGridScreen` or `MediaViewerScreen`. There is
still no pagination — the entire media library is queried into memory on every change.

**Filmstrip sync model:** whoever the user touches leads. When the pager moves, its fractional
position (`currentPage + currentPageOffsetFraction`) is mirrored onto the strip with
`scrollToItem` every frame. Any strip scroll the component did not start itself (drag, snap
fling, thumbnail tap) is treated as a user scrub and each new center item is pushed to the
pager with `scrollToPage`.

**Viewer overlays:** `MediaViewerScreen.isOverlayVisible` is the single visibility state for
the top bar, the filmstrip/action bar **and** the video controls. The overlay heights are
measured and passed to `VideoPlayerView` as `controlsPadding` so nothing overlaps.

### Intentional design notes

- **No `res/` resources.** The manifest deliberately uses framework resources
  (`@android:drawable/ic_menu_gallery`, `@android:style/Theme.Material.NoActionBar`) so the
  project needs no `res/values/themes.xml`. `res/drawable/` and `res/values/` exist but are
  empty. If you add resources that's fine — just don't assume they were forgotten.
- **Edge-to-edge** is enabled in `MainActivity` with forced light system-bar icons, because
  the UI is hardcoded dark (§6.9). Revisit together with the light theme pass.

---

## 5. Testing checklist

### Loading test media — read this before reporting "the grid is empty"

The emulator starts with an **empty** gallery, so the app correctly shows "0 items" until you
add media. Two traps cost an hour of debugging on 2026-09-18; both are now verified.

**Trap 1 — never drag and drop onto the emulator window.** Drag-and-drop lands files in
`/sdcard/Download/`. Under scoped storage, an app holding only `READ_MEDIA_IMAGES` /
`READ_MEDIA_VIDEO` **cannot see files in `Download/`** — access there requires SAF or
ownership. MediaProvider enforces this by *filtering result rows*, not by throwing, so the app
receives a valid empty cursor with no `SecurityException` and no log entry. It is
indistinguishable from "no media exists."

Measured, with the same six files and identical app code:

| Files in | App's cursor | `adb shell content query` |
| --- | --- | --- |
| `Download/` | **0 rows** | 6 rows |
| `Pictures/` | **7 rows** | 7 rows |

`adb shell` runs with shell privileges and is *not* subject to the app's scope — so shell
seeing media proves nothing about what the app can see. When a MediaStore query looks wrong,
compare the app's row count against shell on the same URI: divergence means access scope,
agreement means a real query bug.

**Trap 2 — the `MEDIA_SCANNER_SCAN_FILE` broadcast is dead.** Deprecated since API 29, it
silently no-ops on modern system images. Use the MediaProvider `scan_file` method.

Correct procedure (verified on the `Pixel_9_Pro_XL` / API 37 AVD):

```bash
adb push "C:\path\to\photo.jpg" /sdcard/Pictures/
```

```bash
adb shell content call --uri content://media --method scan_file --arg /sdcard/Pictures/photo.jpg
```

Videos go in `/sdcard/Movies/`, scanned the same way. Verify indexing with:

```bash
adb shell content query --uri content://media/external/images/media --projection _display_name:relative_path
```

When the permission dialog appears, any choice should now work: "Allow all", "Select
photos…" (partial access) or deny → retry / "Open settings" buttons. Please test all three.

Manual smoke test, in order:

1. Grid renders thumbnails; the count in the header is correct; screenshots/downloads appear
   in date order (not at the bottom). Two-finger pinch changes the column count.
2. Tap a photo → fullscreen viewer opens on the right image.
3. **Swipe the pager with one finger → pages change** and the filmstrip tracks 1:1.
4. Scrub the filmstrip / tap a thumbnail → pager follows and lands on the centered item.
5. Tap image → overlays toggle. Double-tap / pinch → zoom; panning stops at the image edge;
   swiping away and back resets the zoom.
6. Open a video → it plays, "Capture" sits below the top bar, the seek bar sits above the
   filmstrip, and tapping the video hides/shows everything together. Drag the seek bar.
7. Swipe video → video: only the settled page plays. Press Home: audio stops.
8. Tap Capture → flash, thumbnail badge, new file in `Pictures/OneGallery_Captures` dated next
   to its source video, with GPS EXIF if the video had a location.
9. Info button → details sheet shows correct resolution / size / path.
10. System back in the viewer → returns to the grid (does not exit).
11. From the Files app, "Open with" OneGallery on a photo → that photo opens. This also works
    for files in `Download/` (Trap 1 above): the VIEW intent carries its own read grant, so it
    does not depend on the MediaStore scope.
12. Delete a file from another app while the viewer is open → no crash.

---

## 6. Known issues

Ranked by how much they will affect a first test run. Section numbers are kept stable so old
references still resolve; issues fixed in the 2026-09-18 review-fix pass are listed at the end.

> **Everything marked "fixed" below is UNVERIFIED** — written without a compiler or device.
> Treat each as "should be fixed, confirm on the AVD" (§5 checklist).

### 6.3 The "instant TextureView" capture path never fires — HIGH (feature degraded)

`VideoPlayerView.kt` (`PlayerView(ctx)` factory and `findTextureView`)

**VERIFIED by decompiling `media3-ui-1.4.1`:** `PlayerView` reads `surface_type` from XML
attributes and defaults to `SURFACE_TYPE_SURFACE_VIEW`. Constructed programmatically as
`PlayerView(ctx)` with no attrs it creates a **SurfaceView**, so `findTextureView()` always
returns `null`. (A SurfaceView's contents can't be read via `getBitmap()` anyway.)

Capture still works, but always through the slower `MediaMetadataRetriever` path in
`VideoSnapshotManager.extractHighResFrame()`, so the thumbnail badge appears only once that
finishes. `VideoSnapshotManager` is already structured for the real thing: the TextureView
frame drives the instant thumbnail only, and the saved file is always the native-resolution
frame.

*Fix:* inflate `PlayerView` from a layout XML with `app:surface_type="texture_view"`, or drop
`PlayerView` and attach your own `TextureView` via `player.setVideoTextureView(...)`.

### 6.4 Tabs and action buttons are non-functional — MEDIUM

`GalleryGridScreen.kt` (bottom nav), `MediaViewerScreen.kt` (action bar)

Albums and Search update `selectedTab` but the content never changes — the grid always
renders Pictures. Share, Edit, Favorite, Delete and More are empty lambdas. `isFavorite` is
hardcoded `false` and never read from `MediaStore.IS_FAVORITE`.

### 6.5 Every video page still builds its own ExoPlayer — LOW (was MEDIUM)

`VideoPlayerView.kt`

Overlapping audio and background playback are fixed (only the settled pager page plays, and
`ON_STOP` pauses). What remains is cost: each composed video page still creates and
`prepare()`s its own player, including pages merely passed while scrubbing the filmstrip.
Consider a single shared player driven by `pagerState.settledPage`.

### 6.9 Grid is hardcoded dark — LOW

`GalleryGridScreen.kt` and throughout. `Theme.kt` defines a complete light color scheme,
but the grid hardcodes `DarkBackground` and `DarkText*`. Light mode looks half-finished.
`MainActivity` forces light system-bar icons to match; undo that when this is fixed.

### 6.13 Partial access can't be widened from inside the app — LOW

With "Select photos…" (Android 14+) the gallery shows only the chosen items and offers no
"select more" entry point. Re-requesting the media permissions re-opens the system picker.

### 6.14 ACTION_VIEW items have synthetic metadata — LOW

`MediaStoreRepository.mediaItemFromUri()` builds a standalone `MediaItem` (id `-1`, no path,
no dimensions, date = now) for content handed over by other apps, so the details sheet is
sparse for those. The viewer shows that one item only — no filmstrip neighbours.

### Fixed on 2026-09-18 (unverified — see note above)

| # | Issue | Resolution |
| --- | --- | --- |
| 6.1 | Permission dead-end on Android 14/15 | `READ_MEDIA_VISUAL_USER_SELECTED` requested; any media grant (full/partial/images-only) counts; retry + "Open settings" buttons; re-check in `onStart` |
| 6.2 | Full MediaStore query on the main thread | Observer only signals; conflated + rate-limited query on `Dispatchers.IO`; flow owned by `GalleryViewModel` |
| 6.6 | Filmstrip drew two overlapping frames | Per-item border removed; the center bracket is the selection marker |
| 6.7 | Grid pinch-to-zoom rarely triggered | Zoom accumulated across the gesture, handled in the Initial pass so scrolling can't cancel it |
| 6.8 | `minSdk = 26` but query needed API 29 | `minSdk` raised to 29; pre-Q branches and `WRITE_EXTERNAL_STORAGE` removed |
| 6.10 | Deprecated icon warning | `Icons.AutoMirrored.Rounded.ArrowBack` |
| 6.11 | Dead `FilmStripLayoutManager.kt` | Deleted (recover from git history if wanted) along with the `recyclerview` dependency |
| 6.12 | Viewer crash when the library shrinks | Page index clamped before indexing |

---

## 7. Changelog

### 2026-09-18 — code-review fix pass (**NOT COMPILED, NOT RUN**)

Written on a machine with no JDK / Android SDK. First job for whoever picks this up: run
`gradlew.bat assembleDebug`, fix any compile errors, then walk the §5 checklist.

Bugs found in review (none were in the previous known-issues list unless noted):

1. **Photo pages couldn't be swiped.** `detectTransformGestures` consumed every one-finger
   drag, starving `HorizontalPager`. Replaced with a gesture loop that only consumes when it
   actually zooms/pans; offsets are clamped to the image; zoom resets when the page leaves.
2. **Viewer overlays covered the video controls and could not be hidden on video pages.**
   Visibility is now one hoisted state; control positions use measured overlay heights.
3. **Permission dead-end** (§6.1).
4. **System back exited the app from the viewer.** Added `BackHandler`.
5. **Media flow rebuilt on every recomposition + main-thread queries** (§6.2). Added
   `GalleryViewModel`.
6. **Items with NULL `DATE_TAKEN` sorted to the bottom.** Sort moved to Kotlin, on the
   resolved date.
7. **Crash when the library shrinks with the viewer open** (§6.12).
8. **Videos autoplayed on every composed page and kept playing in the background** (§6.5).
9. **Filmstrip ↔ pager sync** captured stale parameters, fed its own programmatic scrolls back
   to the pager, could skip a sync, rested 2 dp off-center, and recomposed every thumbnail per
   scroll frame. Rewritten around true 1:1 position mirroring (§4).
10. **`ACTION_VIEW` intent filter was declared but ignored.** Now opens the item (§6.14).

Smaller fixes: snapshot save no longer leaves orphaned `IS_PENDING` rows and writes EXIF
(date, GPS from the video, user comment) before publishing; stills are dated at the video's
capture time + playback position; the 300 ms capture timeout that saved nothing was removed
and the saved file is always the high-res frame; the thumbnail badge uses a 256 px bitmap and
restarts its timer per capture; the seek-bar thumb follows the finger while dragging; the
position ticker stops while paused; edge-to-edge with real window insets instead of fixed
`40.dp`/`48.dp`; `SimpleDateFormat`s hoisted out of composition.

Build/config: `minSdk` 29; removed `viewBinding`, `navigation-compose`, `recyclerview`,
`coil-network-okhttp`, and the `VIBRATE` / `WRITE_EXTERNAL_STORAGE` permissions; added
`lifecycle-runtime-compose`; Coil `3.0.0-rc01` → `3.0.4` (**new artifacts — first build needs
network**).

### 2026-09-18 — first successful run on an emulator

Launched on the `Pixel_9_Pro_XL` / API 37 AVD. The grid loads and renders thumbnails
correctly (7 test images). **No application code was changed** — the committed code was
already correct.

The "empty gallery" symptom that prompted this investigation was caused entirely by the
*testing instructions* in this document, which have been rewritten (§5):

- Media loaded by drag-and-drop goes to `/sdcard/Download/`, which the app cannot read under
  scoped storage. Moving the same files to `/sdcard/Pictures/` made all of them appear, with
  the app code untouched.
- The `MEDIA_SCANNER_SCAN_FILE` broadcast previously recommended here is deprecated and
  no-ops on modern images.

Two hypotheses were investigated and **disproved** — recorded so nobody re-treads them:

- *"`date_taken` is an invalid projection column on the Files collection."* False. The column
  constant is `datetaken`, not `date_taken`; the app's full projection queries fine.
- *"`MediaStore.Files` returns an empty cursor on Android 13+ for apps without
  `READ_EXTERNAL_STORAGE`, so the typed Images/Video collections must be used."* False. The
  existing `MediaStore.Files` query returned all 7 items once the files were in a readable
  directory. A refactor to typed collections was written, tested, found unnecessary, and
  reverted.

Still unexercised on-device: the viewer, filmstrip sync, and video frame capture.

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

1. ★ **Compile the review-fix pass** (`gradlew.bat assembleDebug` + `assembleRelease`) and fix
   whatever the compiler finds. Then restore the VERIFIED markers in §1.
2. ★ Re-run on the `Pixel_9_Pro_XL` AVD and finish the on-device smoke test. Before the
   review-fix pass only the grid was confirmed; the viewer, filmstrip sync and video frame
   capture have never been exercised. Load a photo set and a video (`/sdcard/Movies/`) per §5
   and walk the checklist — especially pager swiping, filmstrip sync feel, and the three
   permission paths.
3. Restore the real TextureView capture path (§6.3).
4. Move `MediaStoreRepository` creation out of `VideoPlayerView` (it builds its own; pass the
   ViewModel's instance or a snapshot callback down instead).
5. Implement Share and Delete via `MediaStore` + `IntentSender` (§6.4).
6. Wire the Albums tab to the already-written `queryAlbums()`, and date headers to
   `queryGroupedByDate()` — both exist in the repository but nothing calls them.
7. Add a shared ExoPlayer across pager pages (§6.5).
8. Light theme pass (§6.9).
9. First tests: `MediaItem.formattedDuration`, `formatFileSize`, the date sort and the
   date-grouping logic are pure functions and trivially unit-testable. There is currently no
   test source set.

---

## 9. Conventions for AI coding agents

- **Verify, don't assume.** Run `gradlew.bat assembleDebug` after any change. This codebase
  previously shipped a state that looked correct and did not compile. If you cannot build
  (no JDK/SDK on the machine), say so loudly in §1 and §7 — as the 2026-09-18 pass did.
- **Don't trust the docs over the code.** `CLAUDE.md` and `README.md` describe intended
  Samsung behavior, some of it aspirational (see §6.3). Code is truth.
- **One dead end to avoid:** `res/` is intentionally empty (§4).
- **Never commit** `local.properties`, keystores, or `app/build/`.
- **Don't bump `compileSdk` down** to silence the AGP warning; suppress it or upgrade AGP.
- **State the scope of your testing** in PR descriptions — say whether you only compiled, or
  actually ran it on a device/emulator.
- **Update §7 and §1** when you change build status or fix a listed issue.
