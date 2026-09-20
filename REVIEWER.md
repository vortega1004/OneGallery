# Review of the uncommitted 2026-09-20 changes

**Reviewer:** OpenCode / GPT-6-Astra  
**Date:** 2026-09-20  
**Base:** `a2a6d4b`  
**Verdict:** Changes requested to the handoff's verification claims. No confirmed
blocking runtime regression found in the application diff.

Reviewed all six modified files: `HANDOFF.md`, `app/build.gradle.kts`,
`FilmStripInfinityViewer.kt`, `GhostVideoControls.kt`, `VideoPlayerView.kt`, and
`MediaViewerScreen.kt`. Line references below refer to the reviewed working tree.

## 1. P2 — The recorded clock readings do not establish exact frame counts

**Locations:** `HANDOFF.md:40-43,97-98,607-609,626-638`;
`app/src/main/java/com/onegallery/app/ui/video/VideoPlayerView.kt:574-588`.

The handoff calls a change from `0:10.7` to `0:11.0` evidence of *exactly* ten
frames at 30 fps. It also says tenths distinguish frames at that rate. The latter
is false, and the former is not established by those readings. The formatter
discards everything below 100 ms; frames at 30 fps are approximately 33.3 ms apart.

Starting at exactly 10,700 ms, all of these produce the same new readout:

| Advance | Target, rounded down to ms | Display |
| --- | --- | --- |
| 9 frames | 11,000 | `0:11.0` |
| 10 frames | 11,033 | `0:11.0` |
| 11 frames | 11,066 | `0:11.0` |

Moreover, `stepFrames` assigns the requested target directly to `currentPositionMs`
(`VideoPlayerView.kt:295-298`). Seeing that target in the UI is not independent
evidence that the decoder rendered the intended frame. The existing
`FrameStepTest` tests pure arithmetic against a mathematical model; it does not
exercise the new anchor state or Media3 decoding.

**Requested change:** Keep the requested one-decimal UI if desired, but correct
the source comment and handoff. Either record independent frame-level evidence
(for example, a clip with burned-in frame numbers, inspected before and after
the burst), or narrow the VERIFIED claim to the observed readout change and mark
rendered-frame exactness as not independently verified. If such evidence was
already collected, document it rather than citing only rounded clock readings.
Also replace “two separate causes, both fixed” with wording that preserves the
explicitly unmeasured status of the latency improvement.

This finding does **not** establish that the anchor fix is wrong, and does not
require changing the unchanged frame-step arithmetic.

## 2. P3 — The current handoff still gives contradictory validation instructions

**Locations:** `HANDOFF.md:71,391-403`.

- The compile-warning row says **Zero**. This review's release compilation
  emitted two Kotlin deprecation warnings for `Icons.Rounded.VolumeOff` and
  `Icons.Rounded.VolumeUp` at `VideoPlayerView.kt:552`. The AGP/compileSdk advisory
  also remains. The icon warnings are not new runtime regressions, but the current
  build-status row is inaccurate.
- Checklist steps 23–24 still direct testers to expect play/pause on the left,
  the jog strip above the buttons, a millisecond readout when paused, and
  right-drag = forward. The new implementation deliberately changes all four.
  Although this section originated with PR #2, it remains the active testing
  checklist and should identify the superseded expectations.

**Requested change:** Update the build-status row and those checklist steps to
match the reviewed implementation.

## Verification performed

- Ran `gradlew.bat assembleDebug assembleRelease test` with Android Studio's JBR:
  **BUILD SUCCESSFUL**. Debug build/tests were up-to-date; release Kotlin
  compilation, release tests, and release packaging executed.
- Reviewed the XML test results: **17 tests, zero failures/errors** per variant
  (11 photo-edit tests, 4 frame-step tests, 2 EXIF tests).
- `git diff --check`: passed (Git printed CRLF normalization warnings).
- Checked the version-pinned Media3 1.8.0 source: default fractional seek
  tolerances are null, as the handoff says. Scrubbing mode also **suppresses
  playback**, beyond disabling audio/metadata tracks; that behavior belongs in
  the handoff's description of the integration.
- Inspected the running emulator's control hierarchy. This was not a fresh
  installation or an independent frame-accuracy, cold-start, rotation, or
  physical-device performance validation of this diff.

## Overall assessment

The smaller filmstrip, separate bottom-bar space, expanded button targets, jog
direction change, and explicit frame-step anchor are coherent changes. I found
no concrete reason in this review to reject Media3 1.8.0 solely because of the
version jump. The documented hardware-performance and codec-coverage limitations
remain limitations, not demonstrated defects.

Approval is withheld for the combined code-and-handoff change because its central
frame-precision verification is stronger than the evidence recorded. Correcting
the claims (or supplying the missing evidence) and synchronizing the checklist
would address the findings above.
