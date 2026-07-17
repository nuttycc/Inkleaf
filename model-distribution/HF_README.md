---
license: other
tags:
  - image-super-resolution
  - ncnn
  - real-esrgan
---

# Inkleaf Models

This repository contains independently downloadable ncnn model assets used by the Inkleaf
Android comic reader.

Published release paths are immutable. Inkleaf verifies the exact byte length and SHA-256 of every
downloaded file before atomically installing a model.

## Real-ESRGAN ncnn-vulkan v0.2.5.0

The files under `releases/realesrgan-ncnn-vulkan/v0.2.5.0` were extracted without modification
from the official Real-ESRGAN release archive:

- Upstream project: https://github.com/xinntao/Real-ESRGAN
- Release archive: https://github.com/xinntao/Real-ESRGAN/releases/tag/v0.2.5.0
- Archive SHA-256: `e5aa6eb131234b87c0c51f82b89390f5e3e642b7b70f2b9bbe95b6a285a40c96`

The upstream project is BSD-3-Clause licensed. The upstream project does not provide a separate
license declaration specifically for the model weights, so consumers should review the upstream
project and release terms for their use case.
