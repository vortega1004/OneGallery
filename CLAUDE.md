# OneGallery (Samsung Gallery Clone for Pixel / AOSP)

A modern Android Gallery application built to bring Samsung One UI Gallery's fluid UX and signature features to non-Samsung devices (Google Pixel, AOSP, Motorola, etc.).

> **Read [HANDOFF.md](HANDOFF.md) first.** It carries the verified build status, the known-issues
> list with file/line references, the work queue, and the conventions for agents working here.
>
> **This file describes intended/target behavior, some of which is not yet implemented.** Where
> this document and the code disagree, the code is truth — and HANDOFF.md §6 records the known
> gaps. Notably, the dual-engine video capture described below currently runs single-engine
> (HANDOFF.md §6.3).

## Reverse-Engineered Architecture Insights (from Samsung Gallery v15.9.00.43)

This project reverse-engineers and ports the standout UX paradigms from Samsung Gallery (`com.sec.android.gallery3d`):

### 1. Photo Strip Infinity Viewer (`FilmStripLayoutManager` & `FilmStripView`)
- **Centered Alignment with Dynamic Padding**: Samsung calculates padding on the horizontal strip as `(viewportWidth / 2) - (itemWidth / 2)`. Both the first and last elements can rest dead-center.
- **Midpoint Fisheye/Magnification (`isResizedArea`, `fitSizeForExpanded`)**: Thumbnails close to the center viewport axis expand smoothly in scale, width, and elevation, creating an optical magnifying glass effect during fast scrubs.
- **Bidirectional 1:1 Pager Synchronization (`onViewPagerScrolled`)**:
  - Pager swipe $\to$ FilmStrip translation: As the user drags the main fullscreen photo, `onViewPagerScrolled(position, offset)` continuously scrubs the bottom thumbnail strip so the current image remains strictly pinned to center.
  - FilmStrip scrub $\to$ Pager page change: Snapping triggers page jumps smoothly without jarring animations.

### 2. Instant Video Frame Snapshotting (`CaptureDelegate` & `SaveVideoCaptureCmd`)
- **Dual-Engine Capture Pipeline**:
  1. *Immediate Render Path*: Obtains instant 0-latency bitmap directly from `TextureView.getBitmap()` on the UI/render surface.
  2. *High-Resolution Precision Path*: Concurrently queries `MediaMetadataRetriever.getFrameAtTime(renderingPositionUs, OPTION_CLOSEST)` or the hardware decoder buffer for full uncompressed native resolution (up to 4K/8K).
- **Zero Interruption Workflow**: Unlike Google Photos (which forces the user into an "Edit" screen to export a frame), tapping the floating shutter icon captures the frame while video keeps playing (or performs a subtle 100ms micro-pause flash).
- **Background MediaStore Insertion**: Encodes to JPEG in a background coroutine, copies video EXIF metadata (timestamp, GPS coordinates, camera model), and commits directly to `MediaStore.Images.Media.EXTERNAL_CONTENT_URI`.
- **Haptic & Visual Feedback**: Subtle white screen flash + animated thumbnail drop badge to the bottom-left corner.

---

## Tech Stack & Architecture

- **Language**: Kotlin 2.0+
- **UI Toolkit**: Jetpack Compose + Material 3 (One UI design language styling)
- **Media Engine**: AndroidX Media3 (ExoPlayer 1.4+) with hardware video surface
- **Image Loading**: Coil 3 (memory caching, thumbnail downsampling, GIF/WebP support)
- **Data Layer**: Coroutines `Flow` + Android `ContentResolver` querying `MediaStore` with live `ContentObserver`
- **Architecture**: MVI / Clean Architecture (Domain, Data, UI presentation)

---

## Key Modules & Source Files

```
app/src/main/java/com/onegallery/app/
├── MainActivity.kt                   # Permission gate, ACTION_VIEW handling, grid/viewer switch
├── GalleryViewModel.kt               # Owns the live MediaStore subscription (StateFlow)
├── data/
│   └── MediaStoreRepository.kt       # Fast async query for photos, videos, albums, live observer
├── domain/
│   └── MediaItem.kt                  # Models: MediaItem, MediaType (Photo, Video, MotionPhoto), Album
├── ui/
│   ├── filmstrip/
│   │   └── FilmStripInfinityViewer.kt # Jetpack Compose implementation of magnifying scrubber
│   ├── video/
│   │   ├── VideoPlayerView.kt         # Media3 ExoPlayer with floating snapshot shutter
│   │   └── VideoSnapshotManager.kt    # Dual-path frame extraction and EXIF background saver
│   ├── viewer/
│   │   ├── MediaViewerScreen.kt       # Fullscreen zoomable pager synchronized with FilmStrip
│   │   ├── MediaDetailsSheet.kt       # One UI swipe-up EXIF and technical info sheet
│   │   ├── ExifDetails.kt             # EXIF reader (camera, exposure, GPS) for the sheet
│   │   └── SwipeUpGesture.kt          # Non-consuming swipe-up detector
│   ├── editor/
│   │   ├── PhotoEditorScreen.kt       # Basic editor: crop & rotate, adjust, save a copy
│   │   ├── PhotoEditState.kt          # Pure edit state + transform/crop/colour math
│   │   └── PhotoEditRenderer.kt       # Applies an edit to a bitmap
│   ├── common/
│   │   └── MediaImageRequests.kt      # Shared Coil requests (thumbnail stand-in / full-size)
│   ├── grid/
│   │   └── GalleryGridScreen.kt       # Pinch-to-zoom 1-5 column grid with sticky date headers
│   └── theme/
│       ├── Color.kt                   # One UI color palette (AMOLED dark / light)
│       └── Theme.kt                   # Squircle cards, rounded bottom sheets
```

---

## Build & Run Instructions

```bash
# Build debug APK
./gradlew assembleDebug

# Install to connected Pixel via ADB
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
