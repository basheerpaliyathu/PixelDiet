# Changelog

All notable changes to PixelDiet are documented here. This project adheres to
[Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- Initial pure-JVM, zero-native-dependency Android image compressor.
- Kotlin coroutines API (`get()`, `getFirst()`, `asFlow()`) and Java-friendly callback API
  (`launch(OnCompressListener)` / `launch(OnMultiCompressListener)`), with cancellation.
- Input sources: `File`, `content://` `Uri` (scoped-storage safe), `InputStream` provider, `Bitmap`,
  and lists of files/uris.
- Output formats: JPEG, PNG, **WebP lossy/lossless** (API-aware), for smaller files than JPEG.
- WeChat-Moments-style "gear" sizing strategy ported from Luban/AdvancedLuban (third/first/custom).
- **`hardCap(kb)`** — hard output-size guarantee on top of any gear. The encoder enforces this via a
  two-phase quality-loop + resize-fallback so the output is *actually* ≤ the target. This is a
  feature Luban 2 removed (`setMaxSize` is absent from its API), making it a direct migration
  blocker for codebases that relied on it.
- `maxSize(kb)` drives the CUSTOM gear's initial geometry; `hardCap(kb)` works on any gear
  (including the default THIRD_GEAR smart sizing) as a final size fence.
- `putGear()` alias for `gear()` — preserves the AdvancedLuban method name for easy migration.
- `androidx.exifinterface` orientation handling read from streams.
- `ignoreBy(kb)`, `setTargetDir`, `setRenameListener`, custom max size/width/height.
- CI that fails the build if any `.so` ends up in the AAR.

### Changed (relative to AdvancedLuban)
- Reimplemented in Kotlin; **removed the RxJava2 dependency**.
- Decodes the image header once; skips bitmap rotation when the EXIF angle is 0.
- Uses `androidx.exifinterface` instead of the deprecated `android.media.ExifInterface`.

### Roadmap
- Optional HEIF/HEIC output via the platform `HeifWriter` on capable API 28+ devices.
- Maven Central publishing.
- AVIF decode → WebP re-encode helper. (AVIF *encode* is intentionally excluded — it would require a
  native encoder and break the no-`.so` guarantee.)
