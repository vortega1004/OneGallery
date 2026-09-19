# OneGallery — Engineering Handoff

**Last verified:** 2026-09-18 at `3b38155`
**Status:** Builds clean. Smoke-tested on the emulator and on a physical Pixel 10 Pro XL
against a real 3,132-item library. Video frame capture works on real hardware. Two
performance problems appear only at that scale (§6.15, §6.16). See §1 for exactly what was
and was not exercised.
**Owner:** Victor Ortega

This document is the single source of truth for anyone — human or AI agent — picking up this
codebase. Everything marked **VERIFIED** was confirmed by actually running it, not inferred
from reading code.

**If you are here to hunt bugs or improve performance, read §6a and §9 first.** §6a lists
things that look like bugs and have already been disproved; §5a explains why the emulator
cannot measure this app's performance problems. Both exist to stop you repeating work that
has already been done.

---

## 1. Current state

Rows say which device they were checked on. Emulator rows are the `Pixel_9_Pro_XL` / API 37
AVD with a 9-item library; the physical row is a Pixel 10 Pro XL with 3,132 items. **Zero
crashes** on either. Anything the emulator's tiny library cannot exercise is called out —
§6.15 and §6.16 are exactly the problems that only appeared at real scale.

| Item | Status |
| --- | --- |
| `gradlew assembleDebug` | **VERIFIED PASSING** — ~21 MB `app-debug.apk` |
| `gradlew assembleRelease` | **VERIFIED PASSING** — 4 MB minified (R8 + `proguard-rules.pro` work) |
| Compile warnings | **Zero** |
| Grid + live refresh | **VERIFIED** — thumbnails render; `ContentObserver` picked up new media 7 → 8 → 9 items with no restart |
| Pager swiping | **VERIFIED** — advances; the old gesture-starvation bug is gone |
| Filmstrip 1:1 sync | **VERIFIED** — active thumbnail spans x582–762, centre 672 on a 1344 px screen (dead centre) |
| Overlay / video-control collision | **VERIFIED** — Capture at y417–477 vs top bar ending y315; seek bar y2260–2392 vs filmstrip starting y2452 |
| Back handling | **VERIFIED** — returns to the grid instead of exiting |
| Permissions: "Allow all" | **VERIFIED** |
| Permissions: deny | **VERIFIED** — "Allow access" + "Open settings" shown; no dead-end |
| Permissions: partial access | **VERIFIED** — with `READ_MEDIA_VISUAL_USER_SELECTED` granted and `READ_MEDIA_IMAGES` denied, the app rendered exactly the 2 selected items |
| Video playback + background pause | **VERIFIED** — plays; no active audio player after leaving the viewer |
| Video frame capture | **VERIFIED** — 1280×720 native-resolution frame at the correct playback position, valid EXIF APP1 with `Captured from…` UserComment, saved to `Pictures/OneGallery_Captures/` and sorted next to its source video |
| `ACTION_VIEW` handling | **NOT EXERCISED** (§6.14) |
| Grid pinch-to-zoom, zoom/pan clamping | **NOT EXERCISED** — automated gestures don't reproduce real multitouch |
| Filmstrip scrubbing *feel* | **NOT ASSESSED** — subjective, needs a human thumb |
| `minSdk` 26 → 29 | **NOT TESTABLE HERE** — accepted as a product decision; drops Android 8/9 |
| Run on physical Pixel | **VERIFIED** — Pixel 10 Pro XL, Android 17 / API 37, 3,132 items (2,451 photos + 681 videos). Video frame capture confirmed working on real hardware. Two performance problems surfaced only at this scale: §6.15, §6.16 |
| Albums tab | **VERIFIED on emulator** — folder grid, drill-down, viewer scoped to the album, full back chain. Also exercised by the owner on the Pixel |
| Grid position restored on exit | **VERIFIED on emulator** — returns to the photo last viewed, even after paging far from the entry point; no jolt when the item is already visible |
| Sorting (albums + media) | **VERIFIED on emulator** — all options present, orders correct, grid/viewer indices stay aligned after a re-sort, selection survives the viewer. "Date taken" vs "Date added" divergence **NOT verifiable on the emulator**: every test file has a null EXIF capture time, so `dateTaken` falls back to `dateAdded` and the two orders coincide by definition |
| Thumbnail decoding | **VERIFIED firing** on emulator (fetch path instrumented and counted); **speedup only measurable on real photos** — emulator test images are 480×360, so there is nothing to save. Owner reports stutter "mostly gone" on the Pixel |
| Launcher icon | **VERIFIED** — adaptive icon renders correctly under the launcher mask |
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

> **Grid browsing state is hoisted into `GalleryApp` on purpose** — selected tab, opened
> album, column count, **and both `LazyGridState`s**. The viewer *replaces* the grid in the
> composition rather than stacking on top of it, so `rememberSaveable` state owned by
> `GalleryGridScreen` is discarded the moment a photo is opened. Add new grid-level state in
> `GalleryApp`, not in the grid.
>
> Sorting lives alongside it: `albumSort` orders the folder list, `mediaSort` orders the media
> inside a folder and on the Pictures grid. The grid and the viewer must build their list with
> the *same* expression — `mediaItems.forAlbum(id).sortedBy(sort)` — because the viewer opens by
> index; any disagreement opens a different photo than the one tapped.
>
> Changing a sort scrolls back to the top via `scrollToTopAfterReorder()`, which **waits one
> frame first**. LazyGrid keys are stable, so on a re-order it re-anchors to the previously
> first-visible item during the measure pass — a plain `scrollToItem(0)` is silently undone.
>
> On exit the viewer also reports the item it ended on (`onVisibleItemChange`), and the grid
> scrolls to it **by id, not index**, so a live MediaStore update that shifts positions cannot
> send the user to the wrong photo. The scroll is skipped when the item is already visible, so
> simply backing out of a photo you can still see does not jolt the list.

```
app/src/main/
├── AndroidManifest.xml
├── res/                          # Only the launcher icon lives here (adaptive + monochrome)
└── java/com/onegallery/app/
    ├── MainActivity.kt           # Permission gate, ACTION_VIEW, grid/viewer switch, hoisted UI state
    ├── OneGalleryApplication.kt  # Supplies the Coil ImageLoader (thumbnail fetcher + cache)
    ├── GalleryViewModel.kt       # StateFlow of the media list (single MediaStore subscription)
    ├── data/
    │   ├── MediaStoreRepository.kt   # ContentResolver queries, ContentObserver, snapshot saver
    │   └── MediaThumbnailFetcher.kt  # Coil fetcher: MediaStore thumbnails for small requests
    ├── domain/MediaItem.kt       # Models, toAlbums(), AlbumSort/MediaSort + their sorts
    └── ui/
        ├── grid/GalleryGridScreen.kt             # Grid, Albums folders, sort menus, bottom nav
        ├── viewer/MediaViewerScreen.kt           # HorizontalPager + zoomable image + overlays
        ├── viewer/MediaDetailsSheet.kt           # ModalBottomSheet with EXIF-ish details
        ├── filmstrip/FilmStripInfinityViewer.kt  # Compose filmstrip, 1:1 synced with the pager
        ├── video/VideoPlayerView.kt              # Media3 PlayerView + capture shutter UI
        ├── video/VideoSnapshotManager.kt         # Dual-path frame extraction
        └── theme/{Color,Theme}.kt                # One UI palettes
```

### Image loading

`OneGalleryApplication` supplies the app-wide Coil `ImageLoader`. `MediaThumbnailFetcher`
serves small requests (≤512 px) from MediaStore's cached thumbnails via
`ContentResolver.loadThumbnail` instead of decoding the original; larger requests (the
fullscreen viewer) fall through to Coil's normal path untouched.

> The fetcher is registered against **`coil3.Uri`, not `android.net.Uri`**. Coil runs mappers
> before fetchers and `AndroidUriMapper` has already converted the platform Uri by then — a
> factory declared over `android.net.Uri` is never invoked, silently, with no error. If you add
> another fetcher, key it the same way.

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

- **`res/` holds only the launcher icon.** The manifest still uses a framework *theme*
  (`@android:style/Theme.Material.NoActionBar`), so there is no `res/values/themes.xml` and no
  strings file — UI text is inline in the composables. That is deliberate, not an oversight.
  Adding resources is fine; just don't assume existing ones were forgotten.
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

## 5a. Measuring performance (read before "optimising" anything)

**The emulator cannot reproduce this app's performance problems.** Its test library is tiny
and its images are small; the two issues that mattered (§6.15, §6.16) were invisible there and
only appeared on a 3,132-item phone with real camera JPEGs. Any perf claim measured on the
emulator is worthless. Measure on hardware with a real library.

Reset counters, exercise the thing, then read:

```bash
adb shell dumpsys gfxinfo com.onegallery.app reset
```

```bash
adb shell dumpsys gfxinfo com.onegallery.app
```

Look at "Janky frames" as a percentage and the 90th/95th/99th percentiles. Compare
**before and after** on the same device and the same library — absolute numbers mean little
across devices.

For allocation churn during a scroll, `adb shell dumpsys meminfo com.onegallery.app` before
and after tells you whether a change is trading CPU for heap.

### Ground rules

- **Reproduce first.** If you cannot measure the problem, you cannot show you fixed it.
- **One change at a time.** Both perf fixes so far were confounded by bundling; the second
  time (thumbnails + memory cache + crossfade) it was impossible to attribute the improvement.
- **State what you measured on.** "Faster" with no device, library size or numbers is not a
  result. See §9.

---

## 6. Known issues

Ranked by how much they will affect a first test run. Section numbers are kept stable so old
references still resolve; issues fixed in the 2026-09-18 review-fix pass are listed at the end.

> The fixes from the 2026-09-18 pass have since been **built and smoke-tested** on the AVD —
> §1 records exactly which were confirmed and which were not reachable by automated testing.

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

### 6.4 Action buttons are non-functional — MEDIUM (tabs fixed)

`MediaViewerScreen.kt` (action bar)

Share, Edit, Favorite, Delete and More are empty lambdas. `isFavorite` is hardcoded `false`
and never read from `MediaStore.IS_FAVORITE`.

**Albums is now implemented** (folder grid → folder contents → viewer scoped to that folder).
**Search is still unimplemented** but now says so on screen instead of silently rendering the
Pictures grid.

### 6.15 Filmstrip scrubbing — LARGELY FIXED, residual stutter

Was: the filmstrip stuttered while scrubbing on a 3,132-item library, and again when backing
out of the viewer.

Fixed by `MediaThumbnailFetcher` (see §4) plus a larger Coil memory cache. Owner's verdict
after testing on the Pixel: **"mostly gone"**. Not closed, because "mostly" is not "gone".

If the remainder needs chasing, it is probably **not** decoding any more — the next suspect is
recomposition: every visible filmstrip thumbnail recomposes as the mirrored scroll position
updates during a pager drag. That is a different fix from this one. Measure before changing
anything, and measure on a real library — none of this reproduces on the emulator.

### 6.16 Grid is slow to first paint at scale — PARTIALLY ADDRESSED

Owner, on the Pixel: "doesn't populate that fast, but it's not unbearable." Thumbnail decoding
is now cheap (§4), but the structural half is untouched: `MediaStoreRepository.queryMediaItems`
still loads the **entire** library into memory on every change, with no pagination. Not
re-measured since the thumbnail work.

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

## 6a. Dead ends — already investigated, do not re-tread

Each of these looked like a bug and was **disproved by testing**. They are recorded because
the code still *reads* as though they might be problems, so a fresh reviewer will find them
again.

| Hypothesis | Verdict |
| --- | --- |
| `date_taken` is an invalid projection column on `MediaStore.Files` | **False.** The constant is `datetaken`, not `date_taken`. The full projection queries fine. |
| `MediaStore.Files` returns an empty cursor on Android 13+ without `READ_EXTERNAL_STORAGE`, so the typed Images/Video collections are required | **False.** The `Files` query returns everything once the files sit in a readable directory. A refactor to typed collections was written, tested, found unnecessary and reverted. |
| The app cannot see media that is definitely on the device | **Not a bug.** Files in `/sdcard/Download/` are invisible to an app holding only `READ_MEDIA_IMAGES`/`READ_MEDIA_VIDEO`, and MediaProvider enforces that by filtering rows, not by throwing. See §5. |
| `res/` was left empty by mistake | **Deliberate.** Framework theme, no strings file. See §4. |
| `FilmStripLayoutManager.kt` needs fixing | **Deleted.** It was an unused RecyclerView port; the Compose filmstrip is what runs. In git history if wanted. |

### Traps that are real, and are load-bearing

Changing either of these back will break things silently, with no error:

- **Coil fetchers must be keyed on `coil3.Uri`, not `android.net.Uri`.** Mappers run before
  fetchers; `AndroidUriMapper` has already converted the platform Uri. A factory declared over
  `android.net.Uri` is simply never invoked. (§4)
- **Re-sorting a keyed LazyGrid re-anchors to the previously visible item** during the measure
  pass, so `scrollToItem(0)` immediately after a sort is silently undone.
  `scrollToTopAfterReorder()` waits a frame. (§4)
- **Grid state must stay hoisted in `GalleryApp`.** The viewer replaces the grid in the
  composition rather than stacking on it, so anything remembered inside `GalleryGridScreen` is
  destroyed when a photo opens. (§4)

---

## 7. Changelog

### 2026-09-18 — thumbnails, sorting, launcher icon

Three pieces of owner-reported work, all now on the Pixel.

**Thumbnail decoding (§6.15, §6.16).** Grid and filmstrip cells were handing Coil the
full-resolution URI, so a 12 MP JPEG was decoded to fill a 44×60 dp cell. `MediaThumbnailFetcher`
serves those from MediaStore's cached thumbnails; the memory cache went to 30% of heap (the
"stutters when backing out" half was re-decoding evicted entries); crossfade off. Owner's
verdict: stutter **"mostly gone"**.

**Sorting.** Folder list: 8 orderings. Media inside a folder and on the Pictures grid: 10,
including **date taken vs date added**, which differ for anything downloaded, transferred or
restored. Both sorts are hoisted into `GalleryApp` so they survive the viewer.

**Launcher icon.** Replaced the framework placeholder with an adaptive icon, monochrome layer
included.

Two traps found and documented in §4 rather than just fixed: Coil fetchers must be keyed on
`coil3.Uri` (a factory over `android.net.Uri` is never called, silently), and re-sorting a
keyed LazyGrid re-anchors to the previously visible item during measure, so scrolling to the
top has to wait a frame.

Not verified: "date taken" vs "date added" producing *different* orders. Every emulator test
file has a null EXIF capture time, so `dateTaken` falls back to `dateAdded` and the two
coincide there. Real photos will diverge; I tried fabricating EXIF and MediaStore on the
emulator would not index it.

### 2026-09-18 — grid position survives the viewer

Owner-reported from the Pixel: scrolling deep into a library, opening a photo and backing out
dumped you at the very top. At 3,132 items that is a workflow killer.

Two parts, same root cause as the album-navigation bug: the grid's `LazyGridState` lived
inside a composable the viewer destroys. Both grid states are now hoisted into `GalleryApp`,
and the viewer reports the item it ended on so the grid returns *to that photo* rather than
merely to the old offset — which matters because the user may have paged or scrubbed the
filmstrip a long way from where they entered.

Verified on the emulator with a 69-item library: scrolled deep, opened an image, paged well
past it, backed out, and landed on the image last viewed (confirmed against the grid's actual
top, which was elsewhere). Opening a photo and backing straight out leaves the scroll offset
untouched.

### 2026-09-18 — first physical-device run, and a working Albums tab

Run on a **Pixel 10 Pro XL** (Android 17 / API 37) against a real 3,132-item library.
**Video frame capture works on real hardware** — the biggest open unknown, now closed.

Owner-reported findings, all recorded above: the filmstrip still stutters (§6.15) and the grid
is slow to first paint (§6.16) — neither reproduces on the emulator's 9-item library, so both
are volume problems. The Pictures/Albums tabs did nothing: the nav selection changed but the
flat Pictures grid always rendered.

Albums implemented in response (`b2e524d`): folder grid derived from MediaStore buckets via
the new pure `List<MediaItem>.toAlbums()`, drill-down into a folder, and a viewer scoped to
the folder you opened from. Derived from the in-memory list rather than re-querying — on a
3k-item device a second full scan is the most expensive thing the UI could do.

That change also fixed a **state-ownership bug** worth remembering: the viewer *replaces* the
grid in the composition rather than stacking on it, so `rememberSaveable` state owned by
`GalleryGridScreen` was thrown away every time a photo was opened. Back from the viewer landed
on the flat Pictures grid instead of the album, and a second back left the app. Grid browsing
state now lives in `GalleryApp`. **If you add more grid-level state, hoist it there too.**

### 2026-09-18 — PR #1 built, smoke-tested and merged (`0b575a3`)

The review-fix pass below was compiled and exercised on `Pixel_9_Pro_XL` / API 37, then merged.
**It needed no code changes to build** — `assembleDebug` and `assembleRelease` both passed
first time, with zero compile warnings.

Verified: pager swiping, filmstrip 1:1 sync and centring, overlay/video-control collision,
back handling, live MediaStore refresh, no background-audio leak, both previously-broken
permission paths, and video frame capture end to end. Zero crashes. See §1 for the full matrix
of what was and was not exercised.

Testing technique worth reusing: `uiautomator dump` beats screenshots for verifying layout
fixes here, because the video controls auto-hide after 3 s and screenshots race the fade. The
dump gives exact pixel bounds, which turns "do these two overlap?" into arithmetic.

### 2026-09-18 — code-review fix pass (written blind, since verified)

Written on a machine with no JDK / Android SDK, hence the original "NOT COMPILED" warning. It
was subsequently built and smoke-tested — see the entry above.

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

1. ★ **Paginate the library load** (§6.16). `queryMediaItems` pulls all 3,132 rows into memory
   on every change. Now the largest remaining structural problem — thumbnail decoding is done.
2. **Chase the residual filmstrip stutter** (§6.15) *only if it still bothers you*. Decoding is
   no longer the bottleneck; the next suspect is per-frame recomposition of visible thumbnails
   during a pager drag. Measure on a real library before changing anything.
3. **Cover the gaps automation couldn't reach** (see §1): `ACTION_VIEW`, grid pinch-to-zoom,
   zoom/pan clamping at image edges, and Albums on the Pixel. These need a human thumb.
4. Restore the real TextureView capture path (§6.3).
5. Move `MediaStoreRepository` creation out of `VideoPlayerView` (it builds its own; pass the
   ViewModel's instance or a snapshot callback down instead).
6. Implement Share and Delete via `MediaStore` + `IntentSender` (§6.4).
7. Wire sticky date headers to `queryGroupedByDate()` — it exists in the repository but
   nothing calls it. (The Albums tab is now done; it derives from `toAlbums()` rather than
   `queryAlbums()`, to avoid a second full-library query.)
8. Add a shared ExoPlayer across pager pages (§6.5).
9. Light theme pass (§6.9).
10. First tests: `MediaItem.formattedDuration`, `formatFileSize`, the date sort and the
    date-grouping logic are pure functions and trivially unit-testable. `toAlbums()` is the
    newest and most valuable target — it owns grouping, cover selection and ordering. There is
    currently no test source set.

---

## 9. Conventions for AI coding agents

### Start here

1. Read **§6a first** — it lists things that look like bugs and are not. It will save you
   re-deriving three dead ends that have already cost a session each.
2. Read **§1** for what is verified and *on which device*. Emulator-verified and
   phone-verified are not interchangeable.
3. For performance work, read **§5a** before touching anything.

### Rules

- **Verify, don't assume.** Run `gradlew.bat assembleDebug` after any change. This codebase
  previously shipped a state that looked correct and did not compile. If you cannot build (no
  JDK/SDK on the machine), say so loudly in §1 and §7 — as the 2026-09-18 pass did.
- **Don't trust the docs over the code.** `CLAUDE.md` and `README.md` describe intended
  Samsung behaviour, some of it aspirational (see §6.3). Code is truth. This file is the
  exception: it is maintained against what was actually run.
- **Report what you measured, on what.** "Faster" or "fixed" without a device, a library size
  and a number is not a result. If you only compiled, say you only compiled. Several findings
  in §7 are recorded as *disproved* precisely because someone checked instead of asserting.
- **Don't regress what §1 marks VERIFIED.** In particular: viewer scoping to an album, grid
  position restoring on exit, grid/viewer index alignment after a re-sort, and the three
  permission paths. All are easy to break with an innocuous-looking refactor and none has an
  automated test.
- **Prefer one change per commit** when it touches performance. Bundled fixes cannot be
  attributed — see §5a.
- **Never commit** `local.properties`, keystores, or `app/build/`.
- **Don't bump `compileSdk` down** to silence the AGP warning; suppress it or upgrade AGP.
- **Update §1, §6 and §7** when you change build status, fix a listed issue, or disprove one.
  A stale §1 is worse than no §1: this document has twice told readers the build was broken
  when it was fine, and once the reverse.

### Useful techniques found the hard way

- `uiautomator dump` beats screenshots for verifying layout and state: it gives exact pixel
  bounds and every `content-desc`, so "do these overlap?" and "which photo is showing?"
  become arithmetic rather than eyeballing. The video controls auto-hide after 3 s and
  screenshots lose the race.
- When a MediaStore query looks wrong, compare the app's row count against
  `adb shell content query` on the same URI. Divergence means access scope; agreement means a
  real query bug. Shell is **not** subject to the app's scoped-storage limits, so shell seeing
  media proves nothing about what the app can see.
- Drive UI one action per command with a dump in between. Batched `input tap` calls outrun the
  UI and silently land on the wrong target — several false results in this project's history
  came from exactly that.
