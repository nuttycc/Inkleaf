# AI Image Enhancement Archive

This document describes the last complete AI image-enhancement implementation before it is
removed from the main development line. The corresponding repository snapshot is intended to be
preserved as the annotated tag `archive/ai-enhancement-v1`.

Use the tag as a reference implementation. Do not merge it wholesale into a future main branch;
start from the then-current main branch and port only the parts that still fit the reader
architecture.

## What the Snapshot Contains

- Reader integration, eligibility planning, foreground prefetch, and status UI in
  `app/src/main/java/com/exio/inkleaf/ui/ReaderScreen.kt`.
- Page planning, memory budgets, strip geometry, inference, and caching in
  `app/src/main/java/com/exio/inkleaf/data/enhancement/`.
- PDF raster sizing and region rendering in
  `app/src/main/java/com/exio/inkleaf/data/PdfComicVolume.kt`.
- Native ncnn, Real-ESRGAN, Real-CUGAN, and Waifu2x integration in `app/src/main/cpp/`.
- Bundled and downloadable model metadata in `app/src/main/assets/enhancement_models/`,
  `app/src/main/assets/THIRD_PARTY_MODEL_LICENSES.txt`, and `model-distribution/`.

## Why It Was Removed

The implementation divides model inference into strips, but it still composes a complete scaled
page bitmap before display and persistence. Typical PDF pages therefore exceed the conservative
output budget on devices with a 256 MiB application heap even though individual inference tiles
would fit.

PDF enhancement also uses a fixed raster baseline capped by `ENHANCEMENT_PDF_MAX_PIXELS`. That
baseline represents a rendered page size, not necessarily the resolution of an embedded source
image or the pixels required by the current viewport. At normal reading zoom, a clear PDF page may
already exceed the display resolution, so whole-page super-resolution adds cost without visible
detail and can alter line work, text, or screentones.

The catalog describes AnimeVideo-v3 as suitable for manga, while the upstream model is primarily
positioned as a compact animation-video model. Future work should validate models separately for
color animation, illustrations, black-and-white manga, and scanned pages.

## Known Diagnostic Result

On 2026-07-22, the snapshot at commit `beff84d8373de97b4f3a722f23b85d96b477da42`
was installed on a device with a 256 MiB heap limit. PDF pages around `2376 x 3366` planned a 2x
output whose RGB565 allocation was about 61 MiB, while the composed-output budget was about
34.1 MiB. Planning therefore returned `STRIP_MEMORY_BUDGET` before model inference.

## Recommended Direction for a Future Version

1. Base eligibility on effective source resolution and the current viewport or zoom target.
2. Enhance only visible regions or bounded tiles; never require a complete scaled page bitmap.
3. Cache by page, zoom bucket, tile coordinates, model revision, and source revision.
4. Keep one cancellable foreground inference active and discard work immediately after navigation.
5. Preserve the original path as the default for already-clear pages and as the fallback for every
   model, allocation, driver, or cancellation failure.
6. Validate output with original/enhanced comparison on low-resolution, clean, compressed, text,
   and screentone-heavy pages before enabling automatic selection.

## Upstream References

- Real-ESRGAN: <https://github.com/xinntao/Real-ESRGAN>
- Real-ESRGAN ncnn Vulkan: <https://github.com/xinntao/Real-ESRGAN-ncnn-vulkan>
- Archived model mirror: <https://huggingface.co/vozzy/inkleaf-models>
