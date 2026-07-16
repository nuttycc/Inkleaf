# Native Third-Party Dependencies

## ncnn

- Version: `20260526` (`e54f7b1`)
- Package: `ncnn-20260526-android-vulkan.zip`
- Package SHA-256: `26909c92eed35afed4a966b5e9e503fcb0a529691ea3f910ec2c94a4fff52804`
- Source: <https://github.com/Tencent/ncnn/releases/tag/20260526>
- License: BSD 3-Clause and bundled third-party notices in `ncnn/20260526/LICENSE.txt`

The four Android ABIs supported by the existing PDF runtime are vendored. `riscv64` is omitted
because the app's PDF dependency does not provide that ABI.

## Real-CUGAN ncnn Vulkan adapter

- Commit: `395302c5c70f1bff604c974e92e0a87e45c9f9ee`
- Source: <https://github.com/nihui/realcugan-ncnn-vulkan>
- License: MIT

Vendored files are limited to the inference adapter and Vulkan preprocessing/postprocessing
shaders. File decoding, command-line handling, and bundled model assets are intentionally omitted.
Local safety patches propagate model/pipeline initialization failures and tolerate partial
construction during cleanup.

## Waifu2x ncnn Vulkan adapter

- Commit: `64914665c45893135c9e50c1c296170a121b9f77`
- Source: <https://github.com/nihui/waifu2x-ncnn-vulkan>
- License: MIT

The same source-selection and local safety-patch policy as Real-CUGAN applies.

## Real-ESRGAN ncnn Vulkan adapter

- Commit: `37026f49824c5cf84062e7c6a5dd71445dcf610f`
- Source: <https://github.com/xinntao/Real-ESRGAN-ncnn-vulkan>
- Adapter license: MIT, including the retained realsr-ncnn-vulkan MIT notice
- Real-ESRGAN project license: BSD 3-Clause

The adapter and four preprocessing/postprocessing shaders are vendored. Inkleaf adds the same
CPU tiling path, error propagation, allocation guards, and runtime shader compilation used by the
other adapters. The CPU tiling path is adapted from the vendored Waifu2x adapter, whose MIT notice
remains in `waifu2x/LICENSE`. Model sessions keep TTA disabled. Official ncnn model files are
extracted from the pinned Real-ESRGAN `v0.2.5.0` release archive and are not bundled in the APK.
