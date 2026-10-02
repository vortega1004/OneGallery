# OneGallery — Engineering Handoff

**Last verified:** 2026-10-02 — a snapshot no longer restarts the video or flicks the viewer
to a neighbour (the viewer follows items by id), and files can be moved into *any* folder once
the user grants All files access. Both reproduced first on the emulator with generated clips,
then fixed, then put through the regression pass in §9. Owner's rule: **never test on his
phone** — reproduce with clips made or downloaded for the purpose.
Before that, 2026-09-28 — files can be moved between albums in the app (grid
multi-select and the viewer's ⋮ menu); rows left behind by another app's moves are repaired;
and play after a frame walk no longer flashes the frame the walk began on. All three
exercised on the `Pixel_9_Pro_XL` AVD — the flash as a before/after A/B, the moves verified on
disk and in MediaStore. None of it has run on the owner's Pixel yet.
Before that (2026-09-21): frame stepping stopped seeking the player: frames are
decoded in runs and walked out of memory, with the frame number on screen. Measured on the
AVD against a 4K clip: **2.5–3.7 s per step before, under the 0.85 s measurement floor after**.
Before that, the viewer stopped resizing when its chrome toggles, taps
that miss a video control do nothing, and videos carry a name/fps/frame-count line. All three
exercised on the `Pixel_9_Pro_XL` AVD against a clip with burned-in frame numbers. Before
that, the GIF frame count was capped at 250 (unit-tested only), and the GIF creator
**merged into `main`**
(`7d1b787`, fast-forward) after the owner used it on their Pixel and reported it working. Debug/release
builds and 29 unit tests pass; GIF creation was exercised on the `Pixel_9_Pro_XL` AVD and
then by hand on the device. The feature has had **no external review** — the `REVIEWER.md`
round covered the video change only.
**Status:** Debug and release builds pass, 45 unit tests pass per variant. Earlier work
was smoke-tested on the emulator and on a physical
Pixel 10 Pro XL against a real 3,132-item library. Video frame capture works on real
hardware. Two performance problems appear only at that scale (§6.15, §6.16). See §1 for
exactly what was and was not exercised.
**Owner:** Victor Ortega

**Current stopping point:** the GIF creator is merged. `main` is **3 commits ahead of
`origin/main`** and nothing has been pushed. The owner's verdict after using it on the Pixel:
*"working well for the most part but needs some cleanup"* — the specifics are not captured
yet, so §6.17 stands as the known list until they are. The owner merged it deliberately
without that cleanup, because **the main-code items in §8 are the priority, not GIF polish**.
Do not repeat the test passes already recorded below.

---

## 0. Note to the reviewer of the 2026-09-20 change

**Historical note:** this section describes the video/sort change, now committed and
merged. It is kept as it was written for that one review round. The GIF creator — merged
afterwards, and reviewed by nobody — is documented in §1, §4, §6.17 and the top of §7.

**Scope note:** the tree under review also contained the sort-persistence change, which was
*newer than `REVIEWER.md`* and has not been reviewed by anyone. It is a separate top entry in
§7. The risk list below covers the video change only; for the sort change the thing to look at
is the resolution rule and its two traps in §4.

**Status: the video change was reviewed once (`REVIEWER.md`, OpenCode / GPT-6-Astra) and
revised.** All three
findings were upheld and fixed — see the review-pass note at the end of §7's top entry. The
frame-precision evidence in §1 is now frame-level rather than clock-based. The items below
are the *original* risk list, kept because they are still the right things to look at.

The full write-up is the top entry of §7 and the per-item evidence is in §1; this is what is
worth your attention rather than a summary of the diff.

**Three things carry real risk:**

1. **Media3 1.4.1 → 1.8.0.** A four-minor-version jump, taken only to get
   `ExoPlayer.setScrubbingModeEnabled`. It compiles with no source changes and runs on the
   AVD, but nothing here exercises HDR, codec fallback or unusual containers, and the owner's
   library is 681 real videos. If you want to reduce blast radius, 1.6.0 is the first release
   with the API.
2. **Scrubbing mode changes player behaviour while it is on.** It disables the audio and
   metadata tracks, raises the codec operating rate, and suppresses playback
   (`PLAYBACK_SUPPRESSION_REASON_SCRUBBING`). It is entered on any user-driven seek
   and left 900 ms after the last one, immediately when play is pressed, and immediately
   before a page auto-plays (that last one closes the obvious escape: paging away mid-scrub and
   back again). Worth checking whether any *other* path can strand a video with no audio —
   lifecycle stop/start, pager recycle. I did not find one; I also did not prove there isn't
   one.
3. **The pager is now inset by a measured height.** `bottomBarHeightPx` comes from
   `onSizeChanged` on the bottom bar, which `AnimatedVisibility` stops composing when hidden —
   so the last measurement is deliberately retained and the inset is gated on
   `isOverlayVisible` instead. First composition inserts one extra layout pass at 0 dp. Check
   this against a fresh cold start and a rotation.

**What the emulator could not answer,** and what the owner should therefore judge on the
Pixel: whether frame stepping now *feels* fast (the AVD decodes in software), and whether the
enlarged hit areas actually stop the mis-taps. Exactness and step-count accuracy *are*
measured — see §1.

**Please don't re-derive** the frame-step arithmetic in `frameStepPositionMs`; it is unchanged
and unit-tested. The bug was never the arithmetic, it was the position it was measuring from —
see the new entry in §6a.

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
| Compile warnings | **Zero from this project's sources** — verified with `gradlew clean` then `assembleDebug assembleRelease testDebugUnitTest --rerun-tasks`, which is the only way to see them (see §9). Four `Icons.Rounded.*` deprecations were live and this row wrongly said zero: `VolumeOff`/`VolumeUp` (`VideoPlayerView`) and `RotateLeft`/`RotateRight` (`PhotoEditorScreen`). All four are fixed by moving to `Icons.AutoMirrored.Rounded.*`, not suppressed. The AGP-vs-`compileSdk` advisory from the toolchain is unrelated to the sources and still prints |
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
| Automated tests | **45 JVM unit tests pass per variant** (`gradlew.bat test`): the previous 22 plus `GifDraftTest` (6) and `GifEncoderTest` (3). GIF tests cover ordered selection, loop sequences, frame editing, dimensions/timing, and independent ImageIO decoding including LZW dictionary resets and colour quantization. UI and MediaStore coverage remains manual |
| GIF image selection | **VERIFIED on emulator** — two endpoints + Select between selected four images in displayed order; long-press drag selected nine consecutive images; dragging toward the bottom edge scrolled and extended the selection to 18. Selection works in Pictures and is wired identically inside albums; the album-specific flow still needs a manual pass |
| GIF editor and export | **VERIFIED on emulator** — duplicate inserted immediately after the active frame, removal restored the four-frame sequence; speed, rotation, square crop and boomerang saved successfully to `Pictures/OneGallery Gifs/`. Independent Pillow decoding: 405×405, six frames, 110 ms each, infinite loop, hashes in A B C D C B order. A nine-image 1080px-canvas export also completed. Reverse preview and pause exercised |
| Frame stepping is off the seek path | **MEASURED on emulator, 4K clip with a 2 s GOP.** One tap, time until the picture changes, five trials each: the old seek path took 2.48 / 2.53 / 2.56 / 3.11 / 3.72 s; the new one had already changed by the first poll every time, and a poll costs ~0.85 s, so its latency is *below what this method can see* rather than measured. Same emulator, same clip, same taps |
| Frame stepping is exact | **VERIFIED on emulator** against burned-in frame numbers. 720p: 150 → ten taps → 160 → one back → 159 → ten back → 149, with the readout naming the frame on screen at every stop. 4K: a step after a seek-bar drag goes 93 → 94, which is the off-by-one that used to cost a wasted tap. Twenty rapid taps move twenty frames |
| The frame readout | **VERIFIED on emulator** — `frame 446 / 600` against burned-in 445 (the readout is one-based), and `frame 238 / 240` on the 4K clip. The count is the container's own `METADATA_KEY_VIDEO_FRAME_COUNT`, not duration × rate. An ellipsis appears when the number has moved and the picture has not caught up yet |
| 4K frames do not exhaust memory | **VERIFIED on emulator** — a 3840×2160 clip opens, steps and walks without an OOM, because the decode run shrinks to two frames at that size. **Not** verified against 4K60 HEVC, which is what the Pixel actually shoots |
| Viewer never resizes when the chrome toggles | **VERIFIED on emulator, pixel-exact.** Paused clip, screenshot with the chrome on and off, compared the bounding box of the burned-in frame number: identical to the pixel (450, 1178, 892, 1375). Same test on a still: identical (130, 502, 1214, 2463). The band is reserved whether the bar is drawn or not |
| A tap that misses a video control does nothing | **VERIFIED on emulator** — six dead spots probed (the detail line, the gap between the two control rows, the far left of the playback row, between the frame-step buttons, between shutter and mute, above the jog strip) and the chrome stayed up for all six. Before the change the row gap hid it. Tapping the picture still toggles, and the top bar no longer toggles either |
| Video controls still work under the tap swallow | **VERIFIED on emulator** — three consecutive bursts of ten rapid next-frame taps moved exactly +10, +10, +10 rendered frames; ten spaced taps +10; jog 336 px right then left went 283 → 275 → 283 (right still steps backward). A sideways swipe started inside the cluster still pages |
| Clip detail line | **VERIFIED on emulator** — reads `framenum.mp4  30 fps · 600 frames`; `ffprobe` on the same file reports 30/1 and 600 frames. Long names are untested against a real camera roll: the name is the part that truncates, by construction |
| Snapshot leaves the video alone | **VERIFIED on emulator, screen recordings.** Before: paused (or frame-walked) video, shutter → playback started on its own ~1.3 s later, in any list the snapshot lands in. After: paused on the same frame for the remaining 8 s of recording; first video in the list likewise; a snapshot taken while playing keeps playing without a hitch; swiping between two videos still autoplays each and pauses the other |
| Which folders a move can reach | **MEASURED on emulator (Android 17, same as the owner's phone).** Without All files access MediaStore refused Download/, Documents/Scans/, a top-level MyStuff/, Music/Art/ and WhatsApp's Media folder ("Primary directory Download not allowed … allowed directories are [DCIM, Pictures]") and accepted Pictures/Screenshots and DCIM/Camera. With it, all seven took the move — photo and video — on disk, and **no consent dialog appeared** |
| All files access from the picker | **VERIFIED on emulator.** Picker shows "Allow moving into any folder" only while some folder needs it; tapping it opens the per-app All files access page; granting it and pressing back re-reads the grant and opens every folder without reopening the picker |
| Moving files between albums | **VERIFIED on emulator, on disk and in MediaStore.** Two photos and a video multi-selected → Move (3) → New album → system dialog "Allow OneGallery to modify 3 items?" → Allow: all three rows now `Pictures/<album>/`, the files physically in that folder and gone from the old ones, toast "Moved 3 items to …", selection cleared. From the viewer's ⋮ → Move to album, into a folder that already held a file of the same name: landed as `refview_sample_15 (1).jpg` beside it, toast says one was renamed, nothing overwritten. The picker greyed Movies ("Photos can't go in Movies/"), the storage root ("Can't move items here") and the source ("Already here"). **Not run:** media-management granted (no-dialog path), an SD card, a batch in the hundreds |
| Play after a frame walk | **A/B on emulator, 4K clip.** Same script on both builds: walk 93 → 97, let the player park on 97, step back to 95, press play. Before: 95, then **93, 93** — the frame the walk started from — then playback. After: 95, then playback |
| Rows whose files are gone | **VERIFIED on emulator.** A MediaStore row inserted for a path with no file appeared as its own album (5 albums / 28 items). On the next launch the app nominated it, the scanner deleted the row (gone from MediaStore, not just hidden), and Albums went back to 4 / 27. The owner's actual trigger — a bulk move in Google Photos — could not be reproduced here |
| GIF frame ceiling (250) | **Moved from the selection to the GIF button** now that selection also feeds moves. The button reads `GIF (n)` and disables past 250 (`GIF: 250 max`); the creator and the renderer still enforce it. Checked that a mixed selection of two stills and a video shows Move (3) and GIF (2), and the creator opens with exactly the two stills. The 250 boundary itself has not been reached on any device |
| GIF creator on the owner's Pixel | **USED BY THE OWNER**, 2026-09-21, on the physical Pixel 10 Pro XL against the real 3,132-item library: it works, and he wants unspecified cleanup. This row records that the feature ran on real hardware, nothing more — no timings, and no named defect list yet |
| GIF rotation and cancellation | **VERIFIED on emulator** — nine-frame draft survived portrait→landscape→portrait; tools scroll beside the preview in landscape. Cancelling an 18-image export with Back returned to the draft; MediaStore and the output directory contained only the two earlier completed GIFs, with no partial file. Large-library / physical-Pixel performance **not measured** |
| Video clock readout | **VERIFIED on emulator** — one decimal at all times (`0:11.0` / `0:20.0`), playing and paused. The old seconds-vs-milliseconds swap no longer resizes the label. Note the readout is a *display* format only: at 30 fps three consecutive frames can share a tenth, so it cannot be used to count frames (it also shows the requested seek target, not the rendered frame) |
| Bottom bar has its own space | **VERIFIED on emulator** — the pager is inset above the filmstrip when the chrome is showing, so a full-height photo ends cleanly above it; the inset drops to zero when the chrome hides, giving true fullscreen |
| Jog strip direction | **VERIFIED on emulator, frame-level** — read off a clip with burned-in frame numbers: a 600 px drag left went frame **406 → 421**, the same drag right went **421 → 406**. Left = forward, and the two are exactly symmetric (±15 frames for 600 px at 12 dp/frame, 480 dpi, less touch slop) |
| Play/pause and mute placement | **VERIFIED on emulator** — both at the right-hand end of the playback bar, play inboard of mute. Pause and resume both confirmed by tap |
| Frame stepping accuracy under burst | **VERIFIED on emulator, frame-level** — evidence is the frame number burned into the video, i.e. what the decoder actually rendered, not the requested target. From a standing pause: 10 rapid taps on next-frame went **405 → 415**; one more tap **415 → 416**; 10 rapid taps on previous-frame **416 → 406**. Exact in both directions, single and burst |
| Frame stepping *latency* | **NOT MEASURED — anywhere.** The AVD decodes in software, so no timing taken here transfers to the Pixel. Scrubbing mode is in, and the rows above show it does not break exactness, but **no before/after latency number exists for it** and none should be quoted until one is taken on hardware (§5a) |
| Ghost control hit areas | **NOT ASSESSED** — enlarged to ≥48 dp with a 5 dp ring beyond each painted circle, but "do I still miss it" needs a real thumb |
| Step-anchor fix, before/after | **VERIFIED that behaviour changed**, at two different precisions. Before the fix, 10 rapid taps moved the readout by ~0.1 s; after, by ~0.33 s. Tenths cannot resolve one frame, but it comfortably separates ~3 frames from ~10, so the *contrast* holds. The exact post-fix count (10) comes from the frame-number clip, not from that readout. There is no frame-level capture of the pre-fix build |
| Sort order survives a cold start | **VERIFIED on emulator** — set Pictures to Name (Z–A), `am force-stop`, relaunch: still Name (Z–A). Same for the album list (Largest on disk) and for an individual album (Date added (oldest)). Kill was a full process stop, not a back-out |
| Sort order is per album | **VERIFIED on emulator** — Pictures on Name (Z–A) while one album is on Date added (oldest); neither disturbs the other, and both survive the cold start above. A folder never sorted by hand follows the last order chosen anywhere (checked: a fresh album opened showing Name (Z–A) inherited from Pictures) |
| Media3 1.4.1 → 1.8.0 | **VERIFIED building and running on emulator** — required for `ExoPlayer.setScrubbingModeEnabled`. No API breakage; `PlayerView` unchanged |
| `viewer-features` (PR #2) | **VERIFIED on emulator** at `1ea2d9f`. Compiles first try (4 deprecation warnings: `RotateLeft`/`RotateRight`/`VolumeUp`/`VolumeOff`); 17 unit tests pass; release APK 4.27 MB. Re-checked the VERIFIED behaviours it touches: frame capture still 1280×720 with EXIF + `Captured from…` UserComment; overlay collision clear (capture y2128, seek y2332, filmstrip y2578); album scoping correct with no stale `viewerAlbumId`; grid restore returns to the photo last viewed. Editor rotate→save copy: 480×360 → 360×480, EXIF carried, original untouched. Zero crashes. Owner independently confirmed on the emulator. **Not exercised:** crop / brightness / contrast / saturation, frame stepping, jog strip, swipe-up tuning, rewritten pinch — all need real touch |

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
    │   ├── MediaMover.kt             # Moves media between folders (RELATIVE_PATH) + the rules
    │   ├── MediaThumbnailFetcher.kt  # Coil fetcher: MediaStore thumbnails for small requests
    │   └── SortPreferences.kt        # Per-album sort orders, persisted with DataStore
    ├── domain/MediaItem.kt       # Models, toAlbums(), AlbumSort/MediaSort + their sorts
    └── ui/
        ├── common/MediaImageRequests.kt          # Coil requests: thumbnail stand-in, full-size + placeholder
        ├── common/PointerModifiers.kt            # swallowTaps(): a miss inside a control cluster does nothing
        ├── move/MoveToAlbumSheet.kt              # Album picker for moves; greys out folders that can't take the batch
        ├── grid/GalleryGridScreen.kt             # Grid, Albums folders, sort menus, bottom nav
        ├── viewer/MediaViewerScreen.kt           # HorizontalPager + zoomable image + overlays + editor overlay
        ├── viewer/MediaDetailsSheet.kt           # ModalBottomSheet: MediaStore fields + async EXIF
        ├── viewer/ExifDetails.kt                 # EXIF reader (camera, exposure, GPS) for the sheet
        ├── viewer/SwipeUpGesture.kt              # Non-consuming swipe-up detector (opens the sheet)
        ├── editor/PhotoEditState.kt              # Pure edit state + transform/crop/colour math (unit-tested)
        ├── editor/PhotoEditRenderer.kt           # Decode / orient / crop / colour -> Bitmap
        ├── editor/PhotoEditorScreen.kt           # Editor UI: crop & rotate, adjust, save copy
        ├── filmstrip/FilmStripInfinityViewer.kt  # Compose filmstrip, 1:1 synced with the pager
        ├── video/VideoPlayerView.kt              # Media3 PlayerView + capture shutter UI
        ├── video/VideoFrameCache.kt              # Frame-indexed decode in runs, for stepping
        ├── video/GhostVideoControls.kt           # Ghost buttons, frame jog strip, frame-step math
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

**Viewer gestures (all on one page, none may starve the others):** the pager owns horizontal
drags; `ZoomableImageView`'s loop consumes only when it really zooms/pans; `swipeUpToReveal`
*never* consumes and backs off if a child consumed or a second finger lands. Swipe-up is
disabled while a photo is zoomed — via `rememberUpdatedState`, **not** by adding/removing the
modifier, because changing the modifier chain mid-pinch can restart the sibling `pointerInput`
blocks and drop the gesture.

**Viewer image loading:** a photo page requests a thumbnail-sized image first (served by
`MediaThumbnailFetcher`, usually already in the memory cache from the grid) and upgrades to the
full-resolution request after it has been the *active* page for 120 ms. The full request uses
`placeholderMemoryCacheKey(uri.toString())` so the thumbnail stays up while it decodes. This
assumes Coil keys un-transformed requests by `uri.toString()`; if that is wrong the only effect
is no placeholder. **Unmeasured** — see §6.15.

**Video:** starts muted (`isVideoMuted` lives in `MediaViewerScreen`, so unmuting carries to the
next video; reopening the viewer re-mutes). A page only `prepare()`s and plays after being the
active page for 150 ms, because filmstrip scrubbing makes every passed page "settled" for a
frame. A thumbnail poster covers the SurfaceView until `onRenderedFirstFrame`.

**Photo editor:** an overlay inside `MediaViewerScreen`, not a destination. Geometry is one
canonical transform — *flip horizontally, then rotate clockwise* — however the buttons were
pressed (`PhotoEditState.flipHorizontally()` folds a new flip in with H·R(θ) = R(−θ)·H). The crop
rect is normalized and lives in post-transform space, so preview and save share it. Preview:
1600 px bitmap + draw-time colour filter. Save: decode ≤4096 px → `PhotoEditRenderer.render` →
`MediaStoreRepository.saveEditedImage` → `Pictures/OneGallery_Edits`, always a **copy**.

**Transitions:** `GalleryApp` swaps grid/viewer inside `AnimatedContent` (the viewer still
*replaces* the grid — state stays hoisted). `closeViewer` must **not** clear `viewerAlbumId`: the
viewer stays composed while animating out and would swap its list mid-fade. Inside the grid, a
second `AnimatedContent` is keyed by `GridDestination.key`; album items are built from the
*destination's* album for the same reason. Grid cells use `animateItem()`.

**Sort preferences** are persisted, not hoisted: `SortPreferencesRepository` (DataStore
Preferences) is owned by `GalleryViewModel` and exposed as one `StateFlow<SortPreferences>`.
Media sort is stored **per album**, keyed by bucket id, with the flat Pictures tab occupying a
reserved key that no real bucket id can collide with. `SortPreferences.mediaSortFor(albumId)`
resolves a screen's order, falling back to `defaultMediaSort` — which tracks the last choice
made anywhere, so a preference expressed once carries to folders the user has not visited
without overriding folders they deliberately set. Enum constants are stored by name and parsed
defensively; renaming a sort option degrades to the default rather than crashing on launch.

Two things follow from this that are easy to get wrong. The viewer must sort by
`mediaSortFor(viewerAlbumId)`, not by whatever the grid is currently showing — its list has to
match the grid that was on screen when the photo was tapped, and the two diverge as soon as the
user backs out into a differently-sorted folder. And the first composition sees the defaults
before DataStore's first emission arrives, which is exactly why the grid's scroll-to-top is
wired to sort *events* rather than to a `LaunchedEffect` on the sort value.

**The viewer's picture area is a fixed rectangle.** Full height minus a bottom band that is
always reserved — `FilmStripHeight + ViewerActionRowHeight +` the navigation-bar inset. The
chrome slides over a layout that has already settled rather than re-measuring the page, because
a viewer that resizes under a mis-tap is worse than one that wastes a band of black. The band is
*derived from constants*, not measured: `AnimatedVisibility` reports zero height until its first
layout pass, so a measured reservation jumps exactly where the viewer opens. The bar is then
given that same height explicitly, so the two cannot drift apart.

**Taps inside a control cluster are swallowed** (`ui/common/PointerModifiers.kt`). The video
page toggles its chrome on tap, so the gaps between ghost buttons used to dismiss the controls
the user was reaching for. It is the *press* that must be consumed — see §6a, this is not
interchangeable with consuming the release.

**The viewer identifies the item on screen by id** (`ViewerAnchor` in `MediaViewerScreen`), not
by page number. A page settling moves it; a change to the list does not, and the pager is held on
that item with `requestScrollToPage`. Which page may play, the top bar and the filmstrip all
derive from it. See §6a for why page numbers are not safe.

**Moving files** is a MediaStore `RELATIVE_PATH` update (`data/MediaMover.kt`), not
copy-and-delete: MediaProvider renames the file on disk, the row keeps its id, and every app
sees the new folder at once. Scoped storage lets an app modify only what it created, so each
batch first goes through `MediaStore.createWriteRequest` — one system dialog per batch —
launched from `GalleryApp`, which hosts the whole flow so the grid's multi-select and the
viewer's ⋮ menu share it. The manifest declares `MANAGE_MEDIA`; if the user grants *Media
management* in Settings (the picker links there), the same request is approved with no dialog.
The picker checks MediaStore's folder rules up front so it can grey out a folder instead of
accepting a move that will throw. For an ordinary app those rules are narrow — photos only under
DCIM/ and Pictures/, videos also Movies/ (measured, §1) — so the manifest also declares
`MANAGE_EXTERNAL_STORAGE`; once the user grants *All files access* every shared folder is open
and the consent dialog is skipped. The picker offers the grant itself, only when some folder
needs it, and re-reads it on resume. Name clashes
become `name (1).ext`, case-insensitively, because shared storage is casefolded. Selection is
therefore no longer GIF-only: every item is selectable and the GIF button takes the stills.

**The library repairs rows whose files are gone.** After each load, any row whose path does not
exist is handed to `MediaScannerConnection.scanFile` once per process; for a genuinely empty
path the scanner deletes the row and the observer re-queries. The app never hides an item on
its own `File.exists()` — see §6a.

**Frame stepping does not use the player.** `VideoFrameCache` decodes runs of consecutive
frames through `MediaMetadataRetriever.getFramesAtIndex` and keeps them, scaled to the display
area and flattened to RGB_565, in an LRU. While a walk is in progress the page draws that
still over the player's own output and `ExoPlayer` is left parked where the walk began; it is
seeked once, on the way out. Stepping is then an index change and a bitmap lookup, and
reversing direction is free because the frame behind you is still in memory.

The player cannot do this itself. An exact seek re-decodes from the previous sync sample, and
scrubbing mode does not help — `ScrubbingModeParameters.DEFAULT` already enables every flag it
has (verified in the 1.8.0 bytecode), so the re-decode is the irreducible cost. Single-frame
stepping is the worst possible case for it: the whole GOP, per frame.

Three constraints shape the cache, all of them in §6a: the run length has to shrink for large
frames, the index ↔ timestamp mapping has two different correct answers depending on how the
position was arrived at, and prefetching must outlive the step that started it.

**Video seeking:** exact seeks are the default and frame stepping needs them, but each one
restarts decoding at the previous keyframe. Two things make that usable. The player is put in
`setScrubbingModeEnabled(true)` for a burst of user-driven seeks — frame steps, jog strip and
seek-bar drags all route through `beginScrub()` — and dropped 900 ms after the last one. It is
not free to leave on: it disables the audio and metadata tracks, runs the codec hot, and
**suppresses playback** via `PLAYBACK_SUPPRESSION_REASON_SCRUBBING` without clearing
`playWhenReady`. That last part is why a finished seek-bar drag calls `endScrub()` immediately
instead of waiting out the timer, and why `resumePlayback()` and the page-activation effect
both clear it before calling `play()`. And the step target is anchored in `stepAnchorMs` rather than
re-read from the player, because during a burst the reported position lags the seek in flight
(§6a). The viewer inset (`reservedBottom`) means the player's own `controlsPadding` is zero.

### Intentional design notes

**GIF creation:** selection IDs and selection mode are hoisted in `GalleryApp`, so closing
the creator restores the selection and grid position. `GalleryGridScreen` supplies stills
in the current displayed sort order, scoped to the current album. Videos and existing GIFs
are excluded. Long-press range selection consumes movement in the **Initial** pointer pass;
a Main-pass drag detector lost the gesture to LazyGrid scrolling during the first selection
recomposition. A separate edge-scroll loop extends the range while the finger dwells near an
edge. The explicit Select between action is enabled for exactly two selected stills.

`ui/gif/GifCreatorViewModel` owns the draft and export job across Activity recreation. Each
frame instance has a unique key, so duplicates of one source URI remain independently
selectable/removable. The first image's decoded, EXIF-oriented aspect ratio defines the
common canvas. Other images fit inside it on black; rotation and normalized crop apply to
the entire canvas. Preview and export share `GifRenderer` and the existing photo crop/rotate
math. The shared `CropOverlay` is now internal rather than private.

The creator has icon-only forward/reverse/boomerang controls (with accessibility descriptions),
a 1–25 fps speed control quantized to GIF centiseconds, frame copy/remove/reorder, and
480/720/1080px **canvas** choices. Cropping can reduce the final dimensions; the editor shows
the actual output dimensions. Boomerang does not repeat endpoints: A B C B | A B C B.
Exports always loop indefinitely.

`GifEncoder` is a dependency-free streaming GIF89a encoder with per-frame weighted median-cut
palettes and LZW. Frames are decoded/rendered/encoded one at a time; the preview uses a bounded
24 MiB LRU, rather than holding all original photos. `GifRenderer.save` inserts an IS_PENDING
MediaStore row and publishes it only after the stream is complete. Cancellation and ordinary
failures delete the unfinished row/file. Output folder: **Pictures/OneGallery Gifs**.

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

Added by PR #2 (`viewer-features`):

13. Open a video → **silent**. Tap the speaker at the end of the seek bar → sound. Swipe to
    another video → still unmuted. Back out and reopen → muted again.
14. Scrub the filmstrip across several videos → none of them starts playing on the way past;
    the one you stop on starts after a beat, showing a poster frame (not black) until then.
15. Swipe **up** on a photo and on a video → details sheet opens. A sloppy diagonal page-swipe
    must *not* open it. Zoom into a photo and drag vertically → pans, no sheet. The sheet shows
    camera / f-stop / shutter / ISO for a camera photo, and location if media-location was
    granted.
16. Open a photo from the grid → it fades/scales in; the photo is never black while loading
    (blurry thumbnail → sharp). Back → shrinks out to the grid, which is where you left it.
    From inside an album, back must still land in that album.
17. Albums → open folder A, scroll down, back, open folder B → B starts at the top. Entering /
    leaving a folder slides; tab changes fade. Nothing flashes "Nothing here." on the way out.
18. Pinch the grid through several column counts **in one continuous pinch** → steps
    repeatedly, cells glide to their new slots, the grid does not scroll under two fingers.
19. Double-tap a photo → zoom animates in and out.
20. Edit (pencil) on a photo → editor opens. Rotate ×3, mirror, drag crop corners, tap 1:1,
    move sliders, **Save copy** → toast, editor closes, a new `*_edited_*.jpg` appears next to
    the original in the grid and in `Pictures/OneGallery_Edits`. Check the saved file matches
    the preview (orientation, crop, colour). Edit is greyed out on videos and GIFs. System back
    closes the editor, not the viewer.
21. **Re-verify frame capture** (step 8) — its save path was refactored.
22. `gradlew.bat test` → `PhotoEditStateTest`, `ExifDetailsTest` and `FrameStepTest` pass.
23. **One-handed video controls (right thumb, Pixel 10 Pro XL).** Holding the phone in the
    right hand only: the shutter is in the lower-right corner and reachable without shifting
    grip; previous/next-frame sit just left of it; the jog strip is on the **same row**, to
    their left. There is **no** play button in the middle of the video — play/pause is at the
    **right-hand end** of the seek bar, inboard of mute. The controls are outlines with almost
    no fill; the picture is clearly visible through them.
    *Supersedes step 6's "Capture sits below the top bar" and the §1 collision row's Capture
    coordinates* — re-measure with `uiautomator dump`: the jog/step/shutter row and the seek
    bar must sit above the filmstrip and not overlap each other. Note the viewer now
    **reserves** the bottom bar's height rather than drawing under it, so "overlap" means
    overlap within the inset page, not with the bar.
    *Superseded by the 2026-09-20 change: play/pause was on the left and the jog strip was on
    its own row above the buttons.*
24. **Frame precision.** Pause, tap next-frame: the picture advances by exactly one frame.
    **Judge this from the picture, not the clock** — the readout is `m:ss.t`, and at 30 fps
    three consecutive frames can share a tenth, so it cannot resolve a single step. It also
    shows the *requested* seek target, not what was rendered. Use a clip with burned-in frame
    numbers (see §5) if you need to prove a count.
    Hold the button → it walks frames. Drag the jog strip slowly → one haptic tick and one
    frame per ~12 dp, **left = forward**; the pager must not swipe while dragging it. Capture
    while stepped → the saved still is the frame on screen. Try a 60 fps and a slow-motion clip
    (the frame rate comes from `videoFormat`, and variable-rate phone footage is approximated).
    *Superseded by the 2026-09-20 change: the paused readout was `m:ss.mmm` and right = forward.*

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

### 6.17 GIF creator limitations / remaining device coverage

Merged into `main` on 2026-09-21 with these open. The owner has used it on the Pixel and
asked for cleanup without naming it, so **this list is a starting point, not his list** —
whatever he actually noticed is not written down anywhere yet.

- Drafts and active saves survive rotation, but are not persisted across process death. Export
  is a ViewModel coroutine, not a durable background job.
- GIF timing in the file is exact in 10 ms units. Preview waits for uncached images to decode,
  so its first cycle (or a very large selection exceeding the cache) may run slower than the
  selected speed. Physical-Pixel latency and large-selection memory behaviour are unmeasured.
- Canvas uses the first image's aspect ratio and fits other images on black. No fill/cover
  mode, background picker, transparency, per-frame durations, or dithering control yet.
- Selection is general (it also feeds Move); the GIF takes only the stills in it, including a
  motion photo's still component — never video, and animated GIFs are not expanded. Between 2
  and `MAX_GIF_FRAMES` (250) frames: past 250 the button disables and reads `GIF: 250 max`.
  There is no way to raise it from the UI.
- Crop presets place a rectangle; free corner dragging is not aspect-locked, matching the
  existing photo editor. Physical-thumb crop/drag ergonomics, album selection, sharing to an
  external app, and storage/access-failure recovery still need manual coverage.

> The fixes from the 2026-09-18 pass have since been **built and smoke-tested** on the AVD —
> §1 records exactly which were confirmed and which were not reachable by automated testing.

### 6.3 The "instant TextureView" capture path never fires — HIGH (feature degraded)

`VideoPlayerView.kt` (`PlayerView(ctx)` factory and `findTextureView`)

**VERIFIED by decompiling `media3-ui-1.4.1`:** (still true in 1.8.0, which the app now uses)
`PlayerView` reads `surface_type` from XML
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

Share, Favorite, Delete and More are empty lambdas. `isFavorite` is hardcoded `false` and never
read from `MediaStore.IS_FAVORITE`. **Edit is implemented** (PR #2): stills only, saves a
copy. Not covered: video trim, GIFs, overwrite-original, free rotation /
straighten, aspect-*locked* crop dragging (presets only place the rectangle), and Ultra HDR
gain maps (the saved copy is SDR).

**Albums is now implemented** (folder grid → folder contents → viewer scoped to that folder).
**Search is still unimplemented** but now says so on screen instead of silently rendering the
Pictures grid.

### 6.15 Filmstrip scrubbing — LARGELY FIXED, residual stutter

Was: the filmstrip stuttered while scrubbing on a 3,132-item library, and again when backing
out of the viewer.

Fixed by `MediaThumbnailFetcher` (see §4) plus a larger Coil memory cache. Owner's verdict
after testing on the Pixel: **"mostly gone"**. Not closed, because "mostly" is not "gone".

PR #2 adds an **unmeasured** candidate fix for the remainder: scrubbing drives the
pager with `scrollToPage`, so every page passed was composed and immediately started a
*full-resolution* decode (photos) or a `prepare()` + autoplay (videos). Photo pages now load a
thumbnail until they have been active for 120 ms, and video pages wait 150 ms before preparing.
It builds and runs, but was written on a machine that cannot run the app and nobody has
reported before/after numbers — per §5a it counts for nothing until it is measured on the
Pixel, and it is its own commit (`9e2c182` / `33b168c`) so it can be reverted
alone.

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
`ON_STOP` pauses). Since PR #2 `prepare()` is deferred until a page has
been active for 150 ms, so pages merely passed while scrubbing no longer spin up a decoder —
but each composed video page still *constructs* an ExoPlayer. A single shared player driven by
`pagerState.settledPage` remains the real fix.

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
- **`pointerInput` keyed on a value that the gesture itself changes restarts mid-gesture.** The
  grid pinch was keyed on `columnCount`: the first column step restarted the block, the
  restarted block waited for a fresh finger-down, and the rest of the pinch leaked through as a
  two-finger scroll. Key on `Unit` and read changing values via `rememberUpdatedState`. The
  same reasoning is why `swipeUpToReveal(enabled = …)` never adds/removes its modifier. (§4)
- **Anything that animates out keeps composing with *current* state.** `closeViewer` must not
  clear `viewerAlbumId`, and the album grid must build its items from the `GridDestination`'s
  album rather than `openedAlbumId` — both are already reset by the time the exit animation
  draws. (§4)
- **A burst of seeks must not measure from `exoPlayer.currentPosition`.** Held step buttons
  and jog drags fire far faster than an exact seek completes, and the position reported while
  one is in flight does not reliably reflect it — so every step in the burst measures from the
  same frame. The step target is now anchored in `stepAnchorMs` and only falls back to the
  player when there is no burst in progress. Verified on the emulator against a clip with
  burned-in frame numbers — 10 rapid taps move exactly 10 rendered frames, in both directions.
  Do **not** try to confirm this from the on-screen clock: it is tenths, and it displays the
  requested target rather than the rendered frame. (§4, §1)
- **To stop a tap reaching an ancestor, consume the press, not the release.**
  `waitForUpOrCancellation` tests for an up *before* it tests for consumption, so it hands the
  release back to the ancestor's `clickable` whether or not a child consumed it; `clickable`
  starts instead with `awaitFirstDown(requireUnconsumed = true)`, which a consumed press does
  stop. `swallowTaps()` was written the wrong way round first and measured as still toggling
  the chrome. Consuming the press is safe for the pager: `scrollable` takes its press on the
  *initial* pass with `requireUnconsumed = false`, before any of this runs. (§4)
- **The viewer's bottom band is derived, not measured.** `MediaViewerScreen` subtracts
  `FilmStripHeight + ViewerActionRowHeight` from the page and hands the bar the same number.
  Change the filmstrip's thumbnail size or the action row's height without changing those
  constants and the picture silently sits over, or short of, the bar. (§4)
- **Never identify the viewer's current item by page number.** Any MediaStore change can insert
  items ahead of it — a snapshot does, every time — and for a frame every page number points one
  item off. Anything keyed on the number then misfires: the video being watched counted as
  inactive, paused, then active again, and activation autoplays. Use `ViewerAnchor.shownId`.
  And re-anchor only when the *list* changes: re-anchoring whenever page and item disagree also
  fires the instant a swipe ends and bounces the user back. (§4)
- **A snapshot's DATE_TAKEN does not stick.** It is set on insert, then MediaStore re-reads the
  published JPEG's EXIF, finds no date and writes NULL — so every snapshot sorts as newest, not
  beside its video as the code intends. Harmless now that the viewer anchors by id; fixing it
  means writing EXIF DateTimeOriginal into the JPEG. (§8)
- **"The player has drawn a frame at or past the target" does not mean the seek landed.**
  The still is handed back when `VideoFrameMetadataListener` reports such a frame — but a walk
  that steps *back* from where the player was parked ends behind the frame the player last drew,
  so that stale frame already qualifies, and it flashed up before playback. Frames only count
  once a `PlayerMessage`, sent after the seek and delivered on the *playback* looper, has run:
  ExoPlayer processes messages in order, so by then the seek is handled and the decoder
  flushed. Delivered on the main looper instead, it could arrive after the target frame and
  leave the handover waiting for its timeout. (§4)
- **Never hide a media row on this app's own `File.exists()`.** A false negative — an access
  quirk, a volume mid-mount — would make a real photo vanish from the gallery for good. Stale
  rows are *nominated* to the MediaStore scanner, which has full access and deletes the row
  only if the file really is gone. (§4)
- **The app's palette is not wired into `MaterialTheme`.** Material 3 components left on their
  defaults pick colours meant for a different background: the new-album text field drew the
  typed name dark grey on the dark dialog. Pass colours explicitly (see `MoveToAlbumSheet`)
  until the theme is fixed properly.
- **`getFramesAtIndex` materialises the whole run at once.** It returns a `List<Bitmap>`, so
  every frame in the run exists at full resolution simultaneously — 33 MB each at 4K, a quarter
  of a gigabyte for a run of eight, on exactly the footage a phone shoots. `VideoFrameCache`
  therefore sizes the run from the source dimensions (eight at 1080p, two at 4K). A fixed run
  length looks fine on a 720p test clip and kills the process on real video. (§4)
- **"Which frame is this position?" has two correct answers.** After an exact seek the player
  shows the first frame at or *after* the requested position; during playback it is the last
  frame at or *before* it. `frameIndexAt` answers the second, so a walk entered straight after
  a seek-bar drag began one frame behind the picture and the first tap appeared to do nothing.
  Fixed by snapping the drag itself to a frame boundary (`nearestFrameIndexAt`) rather than by
  guessing how the position was reached. Both are unit-tested in `FrameIndexTest`. (§4)
- **Prefetching must not be cancelled by the next step.** It was, at first — one job served
  both the demanded frame and the read-ahead — so during exactly the fast walk it exists to
  smooth, every tap killed the decode that would have fed the tap after it. Separate jobs, and
  only the demanded-frame one is cancellable. (§4)
- **Grid state must stay hoisted in `GalleryApp`.** The viewer replaces the grid in the
  composition rather than stacking on it, so anything remembered inside `GalleryGridScreen` is
  destroyed when a photo opens. (§4)

---

## 7. Changelog

### 2026-10-02 — snapshots leave the video alone; moves into any folder

Owner: *"There is a massive bug that starts playing the video after I take snapshot"*; the
video *"disappears briefly and shows me the next video then the first video appears again in the
film strip"* after walking frames toward the end and taking a snapshot, *"only in certain videos
and folders"*; *"Why can I not move certain file to certain folders?"* And: *"You will not be
testing on my phone."*

**Snapshots.** Reproduced with generated clips: a paused or frame-walked video started playing
~1.3 s after the shutter — the time it takes the snapshot to save and the library to re-query.
Cause: the snapshot is inserted into the list the viewer shows, and the viewer identified the
current item by page number; for a frame every number pointed one item off, so the video was
"inactive", paused, then "active" again — and activation autoplays. The same frame drags the
filmstrip and top bar toward a neighbour. Folders the snapshot never enters were unaffected:
that is the "only certain folders". Fixed with `ViewerAnchor` (§4, §6a). The first version
bounced swipes back; the regression pass caught it before it shipped.

**Moves.** The picker's grey-outs were not a bug of their own — measured on the emulator, MediaStore
itself refuses photos anywhere but DCIM/ and Pictures/ for an ordinary app. The fix is All files
access: declared, offered from the picker where it would help, and with it every folder took
the move and the consent dialog disappeared. Failures now say why, in MediaStore's own words.

The visual glitch exactly as described (the strip showing *another video* for a moment) was not
captured on camera here; its cause — page numbers shifting under the viewer — is what was fixed,
and the recordings show the strip holding steady through the same insertion.

### 2026-09-28 — move files in the app; phantom files; no flash after a walk

Owner: *"After I use the frame forward or backward button and hit play the video briefly shows
where I paused from then plays the video from the frame I was on"*; *"If I use photos to move
a file into a different folder one of the files shows to still be in the origin folder... Both
my file explorer and photos show correctly"*; *"I also need to be able to move files around in
the app."*

**The flash** was a timer. The stepped-to still was dropped 150 ms after the seek that precedes
playback — enough on the emulator, not on a phone with 4K — uncovering whatever the player's
surface still held. It is now dropped when ExoPlayer's frame-metadata listener reports a frame
at or past the target being drawn, counted only after the seek has been processed (§6a); and
the player is parked under the still after 400 ms without a tap, so play usually needs no seek
at all. A/B in §1.

**The phantom** is almost certainly a stale MediaStore row: his file manager reads the disk,
Photos keeps its own index, this app trusts MediaStore. The observer could not have lost the
final change (conflate then requery). Rows whose files are missing are now handed to the
scanner, which deletes them. Reproduced with a planted row, not with Photos itself — so if a
phantom *survives a full restart* of the app, the theory is wrong and it needs another look.

**Moving** is new: multi-select in any grid, or ⋮ in the viewer; pick an album or create one
under Pictures; one system confirmation per batch, or none once *Media management* is granted.
Selection had to stop being GIF-only for this, so the 250-frame ceiling moved from the selection
to the GIF button — otherwise no more than 250 files could be moved at once.

Commits `68ccb8c` (flash), `902ca5a` (phantoms), and the move feature. 43 unit tests (8 new for
the move rules; the selection-cap test went with the selection cap).

### 2026-09-21 — frame stepping stops seeking

Owner: *"The video scrubbing and frame by frame buttons are so slowwww. Sometimes when I keep
hitting the frame forward button I need to hit the back frame button 2 times to actually go
back. I never know if its lagging or if the previous frame is actually just the same frame."*

Three complaints, two causes. The speed was the seek: every step re-decoded from the previous
keyframe, and scrubbing mode had nothing left to give (its DEFAULT already sets every flag —
checked in the 1.8.0 bytecode). The "hit back twice" was an off-by-one entering a walk from a
seek-bar position. The third was not a bug at all but a missing readout: with nothing on screen
naming the frame, a slow step and an identical frame look the same.

`VideoFrameCache` decodes runs of consecutive frames by index and keeps them scaled and in
memory; a walk draws those stills over the player and touches `ExoPlayer` once, on exit. The
frame number is on screen throughout, one-based, over the container's exact frame count, with
an ellipsis while the picture is catching up to a number that has already moved. Seek-bar drags
now land on frame boundaries. The seek path is still there for containers that cannot be
addressed by frame, and for a decode that fails after opening.

Measured on the AVD against a 4K clip with a 2 s GOP — one tap, time to the picture changing,
five trials each: **2.48 / 2.53 / 2.56 / 3.11 / 3.72 s before; after, already changed by the
first poll every time**, and a poll costs about 0.85 s, so the new figure is a ceiling rather
than a measurement. Frame-exactness was checked against burned-in frame numbers at 720p and 4K.
Five unit tests added for the index maths (36 total).

**Still unmeasured on the owner's Pixel**, which is where it matters; the emulator decodes in
software. 4K60 HEVC in particular is untested — §8 item 0.

### 2026-09-21 — the viewer holds still

Owner: *"The viewer adjusts itself when I tap the screen to show the ghost UI and filmstrip.
This is not behavior that I want... When I miss the play button it'll hide it, resize and I
have to tap again and the view resizes again."*

Two faults compounding. The bottom band was reserved **only while the chrome was visible**, so
every toggle re-laid out the page — and a tap that missed a ghost button fell through to the
page, which toggles the chrome, so a single miss cost two resizes. Now the band is reserved
unconditionally and taps that land in the cluster but on no control are consumed. The top bar
swallows its own taps for the same reason.

Also: the top bar is shorter (date 16→14 sp, time 12→11 sp, 12 dp of bottom padding down to 4),
the ghost cluster slides in from the bottom edge instead of only fading, the play button is
48 dp painted / 58 dp tappable, and every video carries a `name · fps · frames` line above the
controls. The frame count is duration × rate — exact for constant-rate footage, an estimate
otherwise, and absent entirely when the decoder reports no rate rather than invented from a
default.

Evidence is in §1; the two things that will bite a later change are in §6a.

### 2026-09-21 — a GIF may hold at most 250 frames

The lower bound was 2 and there was no upper one, so "Select between" across a 3,132-item
library was one tap from a multi-minute export and a file measured in hundreds of megabytes.
Owner picked 250.

`MAX_GIF_FRAMES` and `capSelection` live in `GifDraft.kt`. The grid routes tap, sweep and
range fill through a single capped callback rather than capping in three places, and the
sweep also caps *inside* `extend` — past the ceiling an uncapped proposal keeps growing while
the result cannot, so the "did this change?" test would fire on every pointer move for the
rest of the drag. Over the cap the selection header reads "250 selected (max)" and explains
itself; `duplicateActive` becomes a no-op and its button greys out, mirroring how removal
already stops at 2. `GifRenderer.save` and `GifCreatorViewModel.open` hold the line for any
future caller that does not come from the grid.

Note the cap counts *source* frames. A boomerang re-encodes the interior, so 250 frames can
still mean up to 498 decode-and-encode passes on export.

Two tests added (31 total). **Not run on a device** — the emulator library cannot reach 250
selectable images, so nothing but the pure functions has been exercised.

### 2026-09-20 — GIF creator (merged into `main` 2026-09-21 as `7d1b787`)

Owner requested image-to-GIF creation, bulk image selection, icon-only loop direction,
speed, whole-GIF rotation/crop, a full filmstrip, in-place frame copy/removal, and saving to
`OneGallery Gifs`. Implemented those plus edge auto-scroll, the two-endpoint range fallback,
frame reordering, output-size choices, export progress/cancellation, and Share after saving.
Original source images are never edited or deleted by frame operations.

Added `ui/gif/{GifDraft,GifCreatorViewModel,GifCreatorScreen,GifRenderer,GifEncoder}.kt`,
`ui/grid/ImageDragSelection.kt`, and seven meaningful unit tests. Integrated through
`GalleryApp` and the Pictures/album media grids; reused the photo editor's normalized crop
overlay and renderer. No additional runtime dependency.

Verification: `gradlew.bat assembleDebug assembleRelease test` passed, 29 tests per variant.
Manual emulator evidence is itemized in §1. The saved four-image boomerang was read back via
its MediaStore URI and decoded with Pillow: six distinct frame positions, four unique
images with the two reverse interior repeats, 110 ms dwell, 405×405 after rotate+square crop,
NETSCAPE loop count 0 (forever). JVM tests also decode independently with JDK ImageIO;
random indexed pixels round-trip across all LZW code widths and dictionary resets.
No claims of physical-device performance.

Merged 2026-09-21 as a fast-forward — the branch was one commit sitting directly on `main`,
so there is no merge commit and `feature/gif-creator` was deleted. The owner used it on the
Pixel first and merged on that basis; it never went through an external reviewer.

### 2026-09-20 — sort orders are saved, and saved per album

Answers "is the sorting preference saved per album?" — it was not. There was one `mediaSort`
for the whole app, `rememberSaveable`, so choosing an order inside one folder changed it
everywhere and a cold start reset it. There was no persistence layer in the app at all.

Now: `androidx.datastore:datastore-preferences:1.1.1`, a `SortPreferencesRepository` in
`data/`, and one `StateFlow<SortPreferences>` owned by `GalleryViewModel`. Media sort is stored
per bucket id; the album-list sort is a single value. See §4 for the resolution rule and the
two traps it creates.

The design decision worth flagging, since it is a judgement call and not the only reasonable
one: an album with no explicit choice follows **the last order picked anywhere**, rather than a
fixed factory default. Picking "Name (A–Z)" once therefore applies to every folder you have
not deliberately sorted, while folders you *have* sorted keep theirs. The alternative — every
new folder starts at "Date taken (newest)" until set — means setting the same preference over
and over.

Verified on the emulator across full `am force-stop` cold starts, for the Pictures tab, the
album list, and an individual album, including that the three do not disturb each other
(§1). Five new unit tests cover the resolution rule (22 total). **Not covered:** DataStore's
own storage layer, which would need an instrumented test; and nothing here was run against a
library with thousands of albums.

### 2026-09-20 — video control ergonomics and frame-step speed (uncommitted at time of writing)

Six items of owner feedback from testing the merged build on the Pixel, plus one follow-up
asked for mid-change. Built, unit-tested (17 pass) and exercised on the `Pixel_9_Pro_XL` AVD
against a purpose-made 20 s / 30 fps / 2 s-GOP clip — a long GOP so exact seeks really do cost
a re-decode. Per-item results are in §1.

1. **Clock resized itself on pause.** It swapped between `m:ss` while playing and `m:ss.mmm`
   while paused, so the label changed width and shoved the seek bar sideways. Now one format,
   `m:ss.t`, everywhere — position and duration. Tenths are a *legibility* choice, not a
   measurement tool: at 30 fps a frame is 33.3 ms, so three consecutive frames can share a
   tenth. Do not count frames with it.
2. **Chrome ate the picture, and the picture ran under the chrome.** Two changes. The viewer
   now *reserves* the bottom bar's height: `HorizontalPager` is inset by the measured bar while
   the chrome is visible and by nothing when it is hidden, so a photo matching the screen's
   aspect ratio is no longer partly behind the filmstrip. And the chrome itself shrank — the
   filmstrip from 84 dp to 70 dp (thumbnails 44×60 → 38×48, height now derived from the
   thumbnail rather than hardcoded), the action row's vertical padding from 12 dp to 2 dp, and
   the ghost cluster from three rows to two by putting the jog strip, both frame-step buttons
   and the shutter on one row. The jog strip takes the leftover width via `weight(1f)` instead
   of a fixed 220 dp, so it cannot push the buttons off a narrow screen.
3. **Jog direction flipped.** Drag left is now forward. The ticks still travel with the thumb,
   which is the point: the strip reads as film moving under a fixed needle.
4. **Play/pause moved** from the far left of the playback bar to the right-hand end.
5. **Hit areas enlarged.** `GhostIconButton` and `GhostRepeatButton` now paint the circle at
   the requested size but take touches over `max(size + 10 dp, 48 dp)`, so a near miss still
   lands. Row spacing dropped to 4 dp to pay for the extra width, which leaves a *larger*
   visible gap between circles than before.
6. **"Frame stepping is very slow" turned out to be two problems, only one of which was
   speed.** They have very different evidence behind them; keep them apart.
   - *It barely moved — **fixed and measured**.* Each step recomputed its target from
     `exoPlayer.currentPosition`, and during a burst that does not reflect the seek already in
     flight, so every step measured from the same frame. The target is now anchored in
     `stepAnchorMs`. Verified against a clip with burned-in frame numbers, so the evidence is
     the rendered frame rather than the requested one: 10 rapid taps went 405 → 415, one more
     416, 10 back 406. See §1.
   - *Each step costs a re-decode — **addressed, not measured**.* An exact seek restarts at
     the previous keyframe and replays every frame to the target. Media3 was bumped
     1.4.1 → 1.8.0 for `ExoPlayer.setScrubbingModeEnabled`, which keeps the codec primed
     across consecutive seeks. **No latency number exists for this**, here or on hardware —
     the AVD decodes in software, so nothing measured here would transfer (§5a). What *is*
     established: it does not break exactness. `ScrubbingModeParameters.DEFAULT` leaves
     `fractionalSeekToleranceBefore/After` null (read from the 1.8.0 bytecode), and the
     frame-level results above were taken with scrubbing mode active.

   Scrubbing mode is entered on any user-driven seek (frame steps, jog strip, seek-bar drags)
   and left 900 ms after the last one, immediately when play is pressed, immediately before a
   page auto-plays, and immediately when a seek-bar drag finishes. That last one matters
   because enabling scrubbing mode **suppresses playback**
   (`PLAYBACK_SUPPRESSION_REASON_SCRUBBING`) without clearing `playWhenReady` — caught in
   review; a drag taken while playing would otherwise have sat silent for the idle window
   before resuming.

Follow-up during the same pass: play/pause and mute were swapped, so the bar ends
`… | duration | play/pause | mute`.

Also: the capture badge's clearance above the control cluster is now measured
(`onSizeChanged`) rather than a hardcoded 88 dp, which the taller cluster would have collided
with.

**Review pass (OpenCode / GPT-6-Astra, `REVIEWER.md`).** Three findings, all upheld, all
fixed rather than argued down:
- The original frame-accuracy claim rested on a tenths-resolution clock that cannot resolve a
  single frame, and which displays the requested target rather than the rendered frame. Re-run
  against a purpose-built clip with burned-in frame numbers; §1 now cites those, and the
  latency claim is marked unmeasured.
- "Zero compile warnings" was stale — two volume-icon deprecations. Fixed at source with
  `Icons.AutoMirrored.Rounded.*`; the row now says what was re-checked and how.
- Checklist steps 23–24 still described the old control placement, jog direction and paused
  clock format. Updated, with the superseded expectations called out rather than deleted.
- The reviewer also noted that scrubbing mode suppresses playback, which the handoff had not
  said. Confirmed in `media3-common` 1.8.0 and, more usefully, it exposed a real defect in the
  seek-bar path — see item 6.

### 2026-09-18 — `viewer-features` branch, PR #2 (written blind, since built and tested)

Written on a machine with no JDK / Android SDK, as one commit per concern so a regression can
be bisected — hence the "(NOT COMPILED)" in those commit subjects, which was true when they
were written. The owner then built and tested the branch and reported **"everything is fine"**
before merging. Adds `junit:4.13.2`.

What that report does *not* settle, because it was not itemised: whether the 120 ms deferred
full-resolution load actually reduced the filmstrip stutter (§6.15 — a perf claim still needs
before/after numbers per §5a), and how frame stepping behaves on variable-frame-rate footage.

1. `33b168c` **Review fixes.** Grid pinch restarted itself on the first column step (§6a).
   Filmstrip scrubbing started every video it passed; activation now waits 150 ms and
   `prepare()` is deferred (§6.5). Poster thumbnail over the SurfaceView until the first frame.
2. `095d985` **Videos start muted**, with a toggle at the end of the seek bar. State is
   per-viewer-session, not per player.
3. `423a84f` **Swipe up for details**, and the sheet now reads real EXIF off the main thread
   (camera, aperture, shutter, ISO, focal length; GPS when `ACCESS_MEDIA_LOCATION` was granted).
4. `9e2c182` **Transitions.** Grid↔viewer fade+scale, tab fade / album slide, `animateItem()`
   reflow, animated double-tap zoom, thumbnail placeholder → full-res crossfade with the
   full decode deferred until a page is active (§6.15, unmeasured). Also fixes folders opening
   at the previous folder's scroll offset (one `LazyGridState` serves every album).
5. `ea5d63a` **Basic photo editor** (§4, §6.4) plus the project's first unit tests. The
   transform reduction was checked against a brute-force pixel model before commit — the same
   model is `PhotoEditStateTest`. `MediaStoreRepository`'s insert/pending/EXIF/publish/cleanup
   sequence became one helper (`insertJpeg`) shared with frame capture: **re-verify capture.**

6. `cb74809` **One-handed ghost video controls + frame-accurate stepping** (owner request after using
   the Pixel one-handed). Shutter moved from top-left to the lower-right corner; the centre
   play button is gone and play/pause sits at the left end of the seek bar; new previous /
   next-frame buttons (hold to repeat) and a jog strip where drag distance maps to *frames*
   rather than to a fraction of the video. All controls are outline-only and clustered
   lower-right. `frameStepPositionMs` rounds the seek target **down** — ExoPlayer's exact seek
   shows the first frame at-or-after the position, so rounding up lands one frame late; checked
   against a model at 24–240 fps (`FrameStepTest`). Assumes constant frame rate; on
   variable-rate phone footage a step can occasionally be 0 or 2 frames.

Things a "works fine" pass may not have exercised:

- `Icons.Rounded.RotateLeft / RotateRight / VolumeUp / VolumeOff` may emit deprecation warnings.
  The plain variants were chosen blind because they are guaranteed to exist; switch to
  `Icons.AutoMirrored.Rounded.*` if the compiler says so.
- `animateItem()` interacting with `scrollToTopAfterReorder()` on a re-sort.
- The swipe-up detector being too eager or too shy — tune `triggerDistancePx` / the 2:1
  verticality test in `SwipeUpGesture.kt`.
- Backward frame steps re-decode from the previous keyframe and may lag on 4K footage.

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

0. ★ **Measure frame stepping on the Pixel.** The 2026-09-21 rework took stepping off the
   player's seek path entirely and is 3–4× faster on the AVD (§1), but the AVD decodes in
   software and the owner's footage is 4K60 HEVC, which nothing here has touched. Two things
   to watch on hardware: how long `VideoFrameCache.open` takes on a long clip (it runs on the
   first pause and decodes a probe frame), and whether a run of two 4K frames decodes fast
   enough that holding the step button stays smooth. If it does not, the run length and
   `PREFETCH_MARGIN` are the dials. Same run should cover the scrub change from PR #2 (§6.15) —
   keep or revert on numbers. Tune the one-handed control constants (`GhostVideoControls.kt`,
   `SwipeUpGesture.kt`) if reach or sensitivity is off.
1. ★ **Paginate the library load** (§6.16). `queryMediaItems` pulls all 3,132 rows into memory
   on every change. Now the largest remaining structural problem — thumbnail decoding is done.
1a. **GIF creator cleanup** — deliberately *below* the two items above, which is where the
   owner put it. He has used the feature and wants polish, but has not said what; the first
   move is to get that list from him rather than to guess from §6.17.
1b. **Decode the frames a fast walk skips.** Tapping next-frame faster than frames decode
   cancels the decodes for the frames passed over, so stepping *back* into them is a fresh
   decode — seen on the emulator as `frame 101 / 240 …` sitting over frame 103 for a couple of
   seconds. A forward burst could decode the whole span in runs instead of only its endpoint.
   Measure on the Pixel first; hardware decode may make this invisible.
1d. **Date snapshots properly.** Write EXIF DateTimeOriginal (video date + position) into the
   JPEG so MediaStore keeps it; today every snapshot sorts as the newest item (§6a).
1c. **Delete and Share on the multi-select bar.** Selection is general now, so both are small:
   `createTrashRequest` and an `ACTION_SEND_MULTIPLE` intent, beside Move.
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

### Regression pass — run it before handing a build over

The 2026-09-28 build shipped two regressions because each change was tested alone and the
core flows were never re-run. These are scripted against the emulator with generated clips
(burned-in frame numbers, `PXL_*` names, real capture dates in `DCIM/Camera`); none of them
touch the owner's phone.

1. Open a video, pause, walk frames near the end, press the shutter → stays paused on that frame.
2. Same while playing → keeps playing, no hitch.
3. Swipe from one video to the next and back → each autoplays, the other pauses.
4. Walk frames, step back, press play → no flash of the frame the walk began on.
5. Move a photo into Pictures/… without All files access → one consent dialog, file moved on disk.
6. Move into Download/ with All files access → no dialog, file moved on disk.
7. Multi-select stills + a video → Move counts all, GIF counts the stills.

Record the screen (`screenrecord` to `/data/local/tmp`, *not* shared storage — a recording saved
there is indexed as a video and pollutes the library) and read the frames, rather than trusting
a single screenshot.

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

- **Warning counts are only true after `gradlew clean`.** Kotlin's incremental compiler does
  not recompile untouched files, so their warnings never print again. Two deprecations sat
  unnoticed through several builds on 2026-09-20 and two more only appeared on the clean run
  after that. Before claiming anything about warnings, run
  `gradlew clean` then `assembleDebug assembleRelease testDebugUnitTest --rerun-tasks`.
- **To prove a frame count, burn frame numbers into the test clip.** The on-screen clock
  cannot do it: it is tenths (three frames can share one at 30 fps) and it renders the
  *requested* seek target, not what the decoder produced. Generate one with
  `ffmpeg -f lavfi -i color=c=black:s=1280x720:r=30:d=20 -vf "drawtext=fontfile='C\:/Windows/Fonts/arialbd.ttf':text='%{frame_num}':start_number=0:fontsize=260:fontcolor=white:x=(w-text_w)/2:y=(h-text_h)/2" -c:v libx264 -pix_fmt yuv420p -g 60 -keyint_min 60 -sc_threshold 0 framenum.mp4`,
  then read the number off a screenshot. `-g 60` gives a two-second GOP so exact seeks really
  do cost a re-decode. `drawtext` needs an explicit `fontfile=` on Windows or ffmpeg segfaults
  looking for a fontconfig default.

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
