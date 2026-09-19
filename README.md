# OneGallery

A high-performance Android gallery application that replicates the UX and signature features of **Samsung One UI Gallery** (`com.sec.android.gallery3d`) for **Google Pixel** and AOSP devices.

---

## Why this exists

Google Photos on Pixel has two major frustrations for power users migrating from Samsung Galaxy:
1. **Video Still Snapshotting Friction**: Google Photos forces you to tap "Edit", wait for the video editing suite to initialize, navigate to "Export frame", and then export. Samsung Gallery allows **1-tap instant still capture** right from the player without ever interrupting playback.
2. **Missing FilmStrip Infinity Scrubber**: Samsung's bottom thumbnail filmstrip has a custom magnification lens effect centered on the active photo, with bidirectional synchronization with the main fullscreen pager. Google Photos lacks this fluid visual scrub experience.

---

## Reverse Engineering Insights from Samsung Gallery APK (`15.9.00.43`)

We analyzed the DEX bytecode and internal architecture of the latest Samsung Gallery APK:
- **`CaptureDelegate` & `SaveVideoCaptureCmd`**:
  - Employs a dual-path snapshot pipeline:
    1. **Immediate Surface Path**: Grabs the rendered frame buffer directly from `TextureView.getBitmap()` on the UI thread for 0-latency feedback.
    2. **High-Resolution Hardware Path**: Simultaneously queries `MediaMetadataRetriever.getFrameAtTime(renderingPositionUs, OPTION_CLOSEST)` to retrieve the native full-resolution uncompressed video frame.
  - Commits the snapshot asynchronously to `MediaStore.Images.Media.EXTERNAL_CONTENT_URI` with original video EXIF tags (GPS coordinates, camera model, timestamp) copied over.
  - Fires a brief 200ms white shutter flash over the player and animates the captured thumbnail down to the bottom-left corner with haptic feedback.
- **`FilmStripLayoutManager` & `FilmStripView`**:
  - Horizontal list with dynamic center padding: `(screenWidth / 2) - (itemWidth / 2)` allowing first and last items to sit dead-center.
  - Midpoint magnification (`fitSizeForExpanded` / `isResizedArea`): Thumbnails within $\pm 120\text{dp}$ of the screen center expand in width and scale, creating a fisheye lens effect.
  - Bidirectional 1:1 sync with `HorizontalPager`: Swiping the main photo smoothly scrubs the filmstrip, while scrubbing the filmstrip snaps to the nearest photo and changes the page.

---

## Project Structure

```
gallery_clone/
├── CLAUDE.md                             # Dedicated project memory & architecture spec
├── README.md                             # User guide & feature breakdown
├── HANDOFF.md                            # Engineering handoff: status, known issues, work queue
├── build.gradle.kts                      # Root Gradle configuration
├── settings.gradle.kts                   # Project modules & repository mirrors
├── gradlew / gradlew.bat                 # Gradle wrapper (committed — no install needed)
├── app/
│   ├── build.gradle.kts                  # App dependencies (Media3, Coil, Compose BOM)
│   ├── proguard-rules.pro                # Release shrinker config

│   └── src/main/
│       ├── AndroidManifest.xml           # Android 14/15 MediaStore permissions
│       └── java/com/onegallery/app/
│           ├── MainActivity.kt           # Permissions check & screen navigation
│           ├── GalleryViewModel.kt       # Owns the live MediaStore subscription (StateFlow)
│           ├── data/
│           │   └── MediaStoreRepository.kt  # Fast ContentResolver queries & background saver
│           ├── domain/
│           │   └── MediaItem.kt             # Data models (MediaItem, MediaType, Album)
│           ├── ui/
│           │   ├── filmstrip/
│           │   │   └── FilmStripInfinityViewer.kt # Jetpack Compose magnifying scrubber
│           │   ├── video/
│           │   │   ├── VideoPlayerView.kt         # ExoPlayer + floating 1-tap shutter
│           │   │   └── VideoSnapshotManager.kt    # Dual-path frame extraction & EXIF saver
│           │   ├── viewer/
│           │   │   ├── MediaViewerScreen.kt       # Fullscreen zoomable pager + synchronized filmstrip
│           │   │   ├── MediaDetailsSheet.kt       # Swipe-up EXIF and technical details sheet
│           │   │   ├── ExifDetails.kt             # EXIF reader for the details sheet
│           │   │   └── SwipeUpGesture.kt          # Swipe-up detector that opens the sheet
│           │   ├── editor/
│           │   │   ├── PhotoEditorScreen.kt       # Crop & rotate, adjust, save-a-copy UI
│           │   │   ├── PhotoEditState.kt          # Pure edit state + transform math (unit-tested)
│           │   │   └── PhotoEditRenderer.kt       # Applies an edit to a bitmap
│           │   ├── common/
│           │   │   └── MediaImageRequests.kt      # Shared Coil requests (thumbnail / full-size)
│           │   ├── grid/
│           │   │   └── GalleryGridScreen.kt       # Responsive pinch-to-zoom 1-5 column grid
│           │   └── theme/
│           │       ├── Color.kt                   # One UI AMOLED dark & clean light palettes
│           │       └── Theme.kt                   # Squircle cards and rounded bottom sheets
```

---

## Getting started

**Requirements:** Android Studio (Ladybug or newer) and Android SDK platform 35. The Gradle
wrapper is committed, so no separate Gradle install is needed.

1. **File → Open** → select this folder (the one containing `settings.gradle.kts`).
   Do *not* use *Import Project*.
2. Android Studio generates `local.properties` with your SDK path on first sync. It is
   gitignored — never commit it.
3. Sync, then press ▶ to run on a device or emulator.

From the command line:

```bash
gradlew.bat assembleDebug
```

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### Loading test media onto an emulator

A fresh emulator has an **empty** gallery, so the app will correctly show "0 items" until you
add media. Two traps here, both verified the hard way — see [HANDOFF.md](HANDOFF.md) §5.

**Do not drag and drop files onto the emulator window.** That lands them in `/sdcard/Download/`,
and under scoped storage an app holding only `READ_MEDIA_IMAGES` / `READ_MEDIA_VIDEO` cannot see
files in `Download/`. MediaProvider filters them out silently — you get an empty cursor with no
error, which looks exactly like a broken app. Push into a real media directory instead:

```bash
adb push "C:\Users\V\Pictures\sample.jpg" /sdcard/Pictures/
```

Then trigger a scan. The old `MEDIA_SCANNER_SCAN_FILE` broadcast is deprecated since API 29 and
silently does nothing on modern images — use this instead:

```bash
adb shell content call --uri content://media --method scan_file --arg /sdcard/Pictures/sample.jpg
```

Put videos in `/sdcard/Movies/` and scan them the same way.

When the permission dialog appears, "Allow all", "Select photos…" (partial access) and deny →
retry / "Open settings" are all meant to work since the review-fix pass — please test each
(see [HANDOFF.md](HANDOFF.md) §5).

---

## Project status

As of 2026-09-18: builds clean. Smoke-tested with no crashes on an emulator and on a
**physical Pixel 10 Pro XL** against a real 3,132-item library (itemised at `3b38155`); the
viewer-features work on top of that (PR #2) was built and tested on the emulator.

Working: the grid with live MediaStore refresh; **Albums** folder browsing with the viewer
scoped to the folder you opened from; **sorting** for both the folder list (8 orderings) and
the media inside a folder (10, including date taken vs date added); pager swiping; filmstrip
1:1 sync; **grid position restored** to the photo you were viewing when you back out; back
handling; all three permission paths including partial "Select photos" access; video playback;
and video frame capture end to end, confirmed on real hardware.

Added in PR #2: **swipe up for details** with real EXIF (camera, exposure, GPS); videos **start
muted**; a **basic photo editor** (rotate, mirror, crop, brightness/contrast/saturation — always
saves a copy); animated transitions; and **one-handed video controls** — shutter in the
lower-right corner, previous/next-frame buttons and a jog strip for frame-accurate scrubbing,
all drawn as see-through "ghost" outlines.

Performance: thumbnails are served from MediaStore's cached versions rather than decoding
full-size camera JPEGs, which took the filmstrip stutter from bad to "mostly gone" on a real
library. The library is still loaded in full with no pagination — see `HANDOFF.md` §6.16.

Not yet covered: `ACTION_VIEW` ("Open with OneGallery"), grid pinch-to-zoom and zoom/pan
clamping, and Albums on physical hardware. Search is unimplemented and says so. There are
still no automated tests. `HANDOFF.md` §1 has the full matrix of what was and wasn't
exercised, and on which device.

Known issues are documented with file/line references in **[HANDOFF.md](HANDOFF.md)** — read
that before contributing. It also carries the architecture map, the work queue, how to measure
performance meaningfully (§5a), and a list of things that *look* like bugs and have already
been disproved (§6a). If you are reviewing this codebase for bugs or performance, start with
those two sections.

---

## Contributing

1. Branch off `main`.
2. Run `gradlew.bat assembleDebug` before opening a PR — this project has previously been
   committed in a non-compiling state.
3. In your PR description, state whether you only compiled or actually ran the app on a
   device/emulator.
4. Update `HANDOFF.md` §1 and §7 if you change build status or fix a documented issue.
