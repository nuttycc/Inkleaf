# OCR Current Page Implementation Notes

## Scope

- Bundle PP-OCRv6 small and run it locally through the official PaddleOCR Android SDK path.
- Add "识别当前页文字" to the reader long-press menu and existing more menu.
- Downsample the original page to a maximum long edge of 1600 pixels without lossy re-encoding.
- Keep OCR results in memory for the active reading session.
- Render subtle, clickable OCR regions in place; selected regions use the reader accent color.
- Support ordered multi-selection, copy, select all, and character-level selection in a detail surface.
- Leave translation, search, editing, sharing, speech, chapter OCR, and persistent indexing out of the MVP.

## Decisions

- Model: PP-OCRv6 small, bundled in app assets.
- Runtime: official PaddleOCR v3.7.0 Android SDK using ONNX Runtime 1.21.1 and OpenCV 5.0.0.1.
- Module boundary: vendored `:ppocr-sdk` library module; product-owned models remain under app assets.
- Release ABI: arm64-v8a only; development may retain emulator support where practical.
- OCR source: original page, independent of image enhancement state.
- Selection order: user click order.
- Low-confidence results: visible with weaker styling rather than filtered out.
- Navigation: paging exits selection mode but retains the page result in session memory.
- Failure feedback: Snackbar with retry for failures; Snackbar for no detected text.
- Verification: lightweight Kotlin compilation and focused JVM tests; device validation is manual.

## Progress

- [x] Scope and interaction contract confirmed.
- [x] Vendor the official SDK and dependencies.
- [x] Bundle PP-OCRv6 small models.
- [x] Implement OCR data/session layer.
- [x] Implement reader entry points and result overlay.
- [x] Implement selection/detail/copy interactions.
- [x] Add focused unit tests.
- [x] Update open-source license presentation.
- [x] Run pre-migration lightweight verification and post-migration static checks.

## Verification

- The compile, unit-test, merge, and APK results below were produced before the OpenCV 5 migration.
  Per user instruction, no Gradle build or test was run after switching to OpenCV 5.0.0.1.
- Static OpenCV 5 verification confirmed `OpenCVLoader.initLocal()`, `Geometry.minAreaRect()`, and
  `Geometry.getPerspectiveTransform()` exist in the official 5.0.0.1 Android JAR. The downloaded
  arm64 `libopencv_java5.so` and `libc++_shared.so` both report `0x4000` LOAD alignment.
- Source review also confirmed no QuickBird dependency coordinate, `opencv_java4` library name, or migrated
  `Imgproc` geometry call remains in the OCR integration. This is static verification only.
- Existing ignored files under `ppocr-sdk/build/` still describe the pre-migration QuickBird
  dependency. They are stale generated outputs and were intentionally neither rebuilt nor deleted.
- `\.\gradlew.bat :app:compileDebugKotlin` — passed.
- `\.\gradlew.bat testDebugUnitTest` — passed.
- Final offline compile after review fixes — passed.
- JVM test summary: 103 tests, 0 failures, 0 errors.
- `\.\gradlew.bat :app:mergeReleaseNativeLibs --offline` — passed after resolving duplicate
  `libc++_shared.so` packaging.
- `\.\gradlew.bat assembleRelease --offline` — passed in 21m 51s. The release APK is 85,858,575
  bytes and contains only `arm64-v8a` native libraries.
- Model assets:
  - det: 9,880,512 bytes, SHA-256 `d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e`
  - rec: 21,159,378 bytes, SHA-256 `5435fd747c9e0efe15a96d0b378d5bd157e9492ed8fd80edf08f30d02fa24634`
  - rec config: 150,579 bytes, SHA-256 `ab078671bb49f06228eadccd34f1bb501e157f7a047095ffb943ba81512c77d1`
- The first SDK compile exposed an AGP 9 incompatibility with the obsolete Kotlin Android plugin;
  the module now uses AGP built-in Kotlin.
- The imported `ORTSessionManager.kt` was truncated during the initial vendor operation; it was
  replaced with the complete file from the pinned upstream commit before successful compilation.
- Independent reuse, quality, efficiency, and layout reviews were applied. The final fixes include
  shared inference sampling, standard sheet structure, non-overlapping bottom chrome, ordered set
  selection, an eight-page session cache, engine release on reader exit, and lower SDK cold-path
  memory peaks.

## Deviations

- The upstream PaddleOCR Android SDK was initially intended to remain source-identical. Review
  found two avoidable cold-path memory spikes that matter on this low-spec target: a full-page
  Bitmap copy before OpenCV conversion and simultaneous retention of both ONNX model byte arrays.
  The conservative deviation removes the redundant Bitmap copy and loads model assets one at a
  time. Both changes preserve inference behavior and are documented in `ppocr-sdk/UPSTREAM.md`.
- Session OCR results are capped at the eight most recently recognized pages. The agreement called
  for session-memory caching but did not define a bound; the cap prevents a long comic from growing
  the observable result map without limit while preserving immediate backtracking usefulness.
- Pdfium and OpenCV both package `libc++_shared.so`, which made the release native merge fail.
  Inkleaf now uses Android packaging's `pickFirsts` rule. Both candidate arm64 libraries are
  16 KB-aligned; the selected copy must be confirmed again in the next manually built release APK.
- Release-specific `abiFilters.clear()` did not override the four ABIs inherited from
  `defaultConfig`. The conservative fix makes arm64 the default and adds the other three ABIs only
  to debug, so release merging and packaging are truly arm64-only.
- PaddleOCR v3.7.0's Android sample targets OpenCV 4, but Inkleaf uses official OpenCV 5.0.0.1 to
  stay on the current major version and meet 16 KB page-size requirements. The adapter uses
  `OpenCVLoader.initLocal()` instead of a versioned native library name and migrates the four
  geometry calls moved from `Imgproc` to `Geometry`. OCR output requires device regression testing
  because OpenCV 5 changed some contour and interpolation internals.
- The app's license screen only reads `THIRD_PARTY_MODEL_LICENSES.txt`; module-root license files
  are not Android assets. The conservative fix reproduces the Apache 2.0 terms for PaddleOCR and
  OpenCV, plus the ONNX Runtime MIT terms, in the asset that users can actually open.
- Empty OCR results are not cached, so a no-text result can be retried instead of reopening an
  empty selection session. All OCR menu entry points also share the same global busy state, so
  paging during inference cannot expose an action that will be silently ignored.
- Bitmap transform helpers now recycle their owned source even when rotation or scaling fails,
  preventing retries after an OOM from retaining the previous pixel buffer. OCR overlay paths are
  cached by result and viewport, while zoom-dependent strokes are allocated once per draw rather
  than once per text region.
