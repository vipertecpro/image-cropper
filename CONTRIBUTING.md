# Contributing

Thanks for helping improve **ImageCropper**! This is a NativePHP Mobile plugin
with a thin PHP layer and hand-written native UI (SwiftUI + Jetpack Compose).
Contributions of all kinds are welcome — bug reports, docs, and code.

## Getting set up

Work on the plugin alongside a NativePHP app by pointing Composer at your local
checkout:

```json
"repositories": [
    { "type": "path", "url": "../image-cropper" }
]
```

```bash
composer require vipertecpro/image-cropper:@dev
php artisan native:plugin:register vipertecpro/image-cropper
php artisan native:run ios       # or: android — recompiles the native code
```

The [demo app](https://github.com/vipertecpro/supernativephp-image-manipulation)
is the fastest way to exercise every option end-to-end on a simulator/device.

## Running the tests

```bash
vendor/bin/pest
```

Please keep tests green and add coverage for behaviour you change.

## Project layout

```
src/ImageCropper.php                        PHP entry point — builds the bridge config
src/Facades/ImageCropper.php                the ImageCropper facade
src/Events/ImageCropped.php                 success event (path, id)
src/Events/CropCancelled.php                cancel event (id)
src/Commands/CopyAssetsCommand.php          copies native source into the app build
resources/ios/ImageCropperFunctions.swift   SwiftUI crop view + Core Graphics renderer
resources/android/ImageCropperFunctions.kt  Compose crop view + Canvas/Matrix renderer
resources/js/imageCropper.js                 JS bridge for legacy web-view apps
resources/boost/guidelines/core.blade.php    Laravel Boost / AI usage guidelines
nativephp.json                              plugin manifest: bridge_functions + events
```

## How it works

```
PHP  ImageCropper::open($path, $options)
  └─ nativephp_call("ImageCropper.Open", {...})              ← synchronous native bridge
        └─ Native  ImageCropperFunctions.Open.execute()
              ├─ present a full-screen crop editor over the current screen
              │     • iOS:     UIHostingController → SwiftUI CropView
              │     • Android: ComposeView overlay → CropScreen
              ├─ freehand gestures, all at once (drag + pinch-zoom + two-finger rotate)
              ├─ on "Done": render the crop region to a new file (Core Graphics / Canvas+Matrix)
              └─ dispatch  ImageCropped { path }   (or CropCancelled)
PHP  #[On(ImageCropped::class)] handler receives the path
```

The crop geometry is identical on both platforms: the image-transform anchor and
the crop frame share one centre, so a single affine map takes a source pixel to
output space. The on-screen preview uses that same math, so it's WYSIWYG. The
whole map is set up once in the renderer and the image is drawn through it.

## Extending it

The render math is driven entirely by the crop-frame `viewport`, so most
enhancements are localized to the two renderer functions:

- **Aspect-ratio picker inside the screen** — add buttons that mutate the frame
  size (`viewport`); the renderer already keys off it.
- **Draggable crop-rect corners** — add corner hit-testing + drag handlers that
  resize `viewport`; the render math is unchanged.
- **More filters / adjustments** — apply a `CIFilter` (iOS) / `ColorMatrix`
  (Android) to the bitmap in the renderer before writing the file.

### Notes for native changes

- **Rotation sign.** SwiftUI and Compose report clockwise-positive rotation and
  the renderers use it directly. If a rotated crop comes out mirrored, negate the
  rotation in the renderer (`transform.rotation` in Swift / `state.rotationDeg`
  in Kotlin).
- **Very large images** are cropped on a background thread; consider downsampling
  on decode for enormous sources.

## Submitting changes

1. Open an issue first for anything non-trivial, so we can agree on the approach.
2. Branch, make your change, keep `vendor/bin/pest` green.
3. Update `CHANGELOG.md` if the change is user-visible.
4. Open a pull request against `main` with a clear description and, for native
   changes, a note on which platform(s) you tested on-device.

## Releasing (maintainers)

See [RELEASING.md](RELEASING.md) for the tag-and-publish checklist.
