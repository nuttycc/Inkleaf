# Upstream source

- Project: PaddlePaddle/PaddleOCR
- Release: v3.7.0
- Commit: b03f46425e8ff4442b268ce449e3eef758146cd4
- Imported path: `deploy/ppocr-android/ppocr-sdk`
- License: Apache License 2.0 (see `LICENSE`)

The Kotlin SDK sources are kept in their upstream package (`com.paddle.ocr`).
The Gradle module configuration is adapted to Inkleaf's build versions. Two small low-memory
patches are also applied: Bitmap-to-Mat conversion avoids a redundant full-page Bitmap copy, and
ORT model assets are read one at a time instead of retaining both model byte arrays concurrently.
Inkleaf also targets official OpenCV 5.0.0.1: native initialization uses `OpenCVLoader.initLocal()`
and geometry calls moved out of `Imgproc` use the OpenCV 5 `Geometry` API.
