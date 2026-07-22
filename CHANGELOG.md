# Changelog

All notable changes to `vipertecpro/image-cropper` are documented here.
The format is based on [Keep a Changelog](https://keepachangelog.com), and this
project adheres to [Semantic Versioning](https://semver.org).

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

[1.1.0]: https://github.com/vipertecpro/image-cropper/releases/tag/v1.1.0
[1.0.2]: https://github.com/vipertecpro/image-cropper/releases/tag/v1.0.2
[1.0.1]: https://github.com/vipertecpro/image-cropper/releases/tag/v1.0.1
[1.0.0]: https://github.com/vipertecpro/image-cropper/releases/tag/v1.0.0
