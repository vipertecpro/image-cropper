# Changelog

All notable changes to `vipertecpro/image-cropper` are documented here.
The format is based on [Keep a Changelog](https://keepachangelog.com), and this
project adheres to [Semantic Versioning](https://semver.org).

## [1.3.0] - 2026-07-28

### Added
- **Crop straight from a URL** — `ImageCropper::open()` now also accepts an
  http(s) URL. The native side downloads the image (themed loading screen with
  Cancel, 30s timeouts, 64 MB cap) and then opens the same editor as for local
  files. Download or decode failures fire `CropCancelled`.
- **Croppable-formats-only validation** — sources are gated on the croppable
  extension allowlist (`jpg`, `jpeg`, `png`, `gif`, `webp`, `bmp`, `heic`,
  `heif`, `avif`): anything else throws `InvalidArgumentException` before the
  bridge is called, and the native side additionally verifies the actual bytes
  decode as an image. The Android manifest now declares the `INTERNET`
  permission for remote sources.

## [1.2.0] - 2026-07-27

### Added
- **`theme` option** — hand the editor your app's colors (`background`, `text`,
  `accent`, `highlight` hex keys, all optional) and it renders in YOUR theme on
  both platforms instead of its own; omitted keys keep the system-adaptive
  light/dark defaults.

### Changed
- **Android editor icons now mirror iOS exactly** — the crop-mode button and
  rect presets use the SF-Symbols-style crop glyph, and circle presets use a
  person-in-circle glyph, instead of generic shapes.
- **Pixel-true centring** — the mode switcher is centred on the screen (not
  approximately between Cancel/Done), and the preset/filter strips centre when
  their content fits (still scrolling when it doesn't) — both platforms.

### Fixed — stability hardening (both platforms, verified on simulator/emulator)
- **Guaranteed exactly one result event per editor session.** A double-tap on
  Done (or Done racing Cancel) could fire two events; a second `open()` while an
  editor was up could orphan it. Both sides now use a single-shot finish guard
  and reject re-entrant opens with `CropCancelled`.
- **Android: system Back press dispatched no event** — the PHP side waited
  forever. Back is now intercepted and routed through the cancel path, and the
  device orientation is locked while the editor is open (a rotation would
  destroy the overlay with no event).
- **Android: `OutOfMemoryError` on large photos.** The source is now decoded
  downsampled (`inSampleSize`, longest edge capped relative to `outputSize`),
  the whole render is wrapped so any failure resolves as `CropCancelled`
  instead of crashing, `outputSize` is clamped to 16–4096, the pre-rotation
  bitmap is recycled, and the filter thumbnails use a small scaled copy instead
  of the full-resolution bitmap (which could also blank the preview on GPUs
  with a 4096px texture limit).
- **Android: EXIF `TRANSPOSE`/`TRANSVERSE` orientations** (rotate + flip, from
  some cameras) were ignored — photos came out sideways/mirrored.
- **iOS: compiled against iOS 16-only API.** `url.path(percentEncoded:)` broke
  the stated iOS 15 minimum; replaced with the 15-safe `url.path`.
- **iOS: memory-bounded decode.** The source image is now decoded via ImageIO
  downsampled to a display-appropriate size with EXIF orientation baked in,
  instead of full-resolution.
- **iOS: degenerate inputs guarded.** Zero-size images cancel cleanly; a Done
  tapped before layout no longer produces a blank crop; ruler drags on a
  zero-width track can no longer inject NaN into the transform.
- Output filenames now use UUIDs (same-millisecond crops could collide).

## [1.1.0] - 2026-07-23

### Changed
- **Require PHP 8.4+** — dropped support for PHP 8.2 / 8.3.

### Added
- Total-downloads and PHP-version badges in the README.

## [1.0.2] - 2026-07-23

### Fixed
- **iOS:** the crop editor failed to open when launched right after the gallery
  picker was dismissed — iOS silently refuses to present a screen while another
  is mid-dismiss. It now retries until the top view controller is idle, so
  picking a photo reliably opens the editor. (Android already deferred correctly.)

## [1.0.1] - 2026-07-23

First complete, usable release.

### Added
- Native crop editor (SwiftUI on iOS, Jetpack Compose on Android): freehand 2D
  drag, pinch-zoom and two-finger rotate behind a crop frame.
- Crop presets (profile, square, portrait, 16:9, cover, banner, story), circle
  or rectangle, switchable live in-screen.
- Colour adjustments (brightness / contrast / saturation) and one-tap filters,
  baked into the exported file.
- Configurable `modes` (crop / adjust / filter) and `tools` (zoom / rotate) —
  build a bare crop-only editor, or an adjust-only / filter-only one.
- No-crop modes export the whole photo (longest edge = `outputSize`).
- Theme-aware UI (light / dark), a title bar, and a rotation-aware pan clamp.

### Notes
- Zero third-party native dependencies, no permissions, no network access.

## [1.0.0] - 2026-07-22

- Initial release. **Incomplete — superseded by 1.0.1.** Please use 1.0.1 or newer.

[1.3.0]: https://github.com/vipertecpro/image-cropper/releases/tag/v1.3.0
[1.2.0]: https://github.com/vipertecpro/image-cropper/releases/tag/v1.2.0
[1.1.0]: https://github.com/vipertecpro/image-cropper/releases/tag/v1.1.0
[1.0.2]: https://github.com/vipertecpro/image-cropper/releases/tag/v1.0.2
[1.0.1]: https://github.com/vipertecpro/image-cropper/releases/tag/v1.0.1
[1.0.0]: https://github.com/vipertecpro/image-cropper/releases/tag/v1.0.0
