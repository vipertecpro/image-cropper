# ImageCropper — a native freehand image cropper for NativePHP Mobile

A **fully native, hand-written** image cropper. It opens a real native screen
(SwiftUI on iOS, Jetpack Compose on Android) where the user manipulates the image
**freehand** — drag to reposition (2D), pinch to zoom, and rotate with two fingers,
all at once — behind a crop frame. On confirm it renders the cropped region to a
**real JPEG file** and hands the path back to PHP via an event.

No third-party libraries (no TOCropViewController, no uCrop) — every line is in
this package and yours to extend.

Why a plugin? The on-device PHP runtime has **no image extensions** (no GD/Imagick),
and EDGE's gesture layer has **no 2D drag or rotate gesture**. Native code is the
only way to get freehand cropping *and* a real cropped file. This plugin provides both.

---

## Requirements

- NativePHP Mobile v3 or v4 (`nativephp/mobile: ^3.0|^4.0`)
- iOS 15+ / Android API 26+

---

## Installation

### From a local path (development / this repo)

1. Require it, publish the plugin service provider (once), then register it:

   ```bash
   composer require vipertecpro/image-cropper
   php artisan vendor:publish --tag=nativephp-plugins-provider   # once per app
   php artisan native:plugin:register vipertecpro/image-cropper
   php artisan native:plugin:list        # verify it shows "ImageCropper" + "ImageCropper.Open"
   ```

   **Local development** (working on the plugin next to your app) — point Composer at
   the checkout instead, then require the same way:

   ```json
   "repositories": [
       { "type": "path", "url": "../image-cropper" }
   ]
   ```

   ```bash
   composer require vipertecpro/image-cropper:@dev
   ```

3. Rebuild so the native code compiles in (run in a terminal — pick your platform):

   ```bash
   php artisan native:run ios       # or: android
   ```

> Requiring with Composer is **not** enough — an unregistered plugin does nothing.
> Always run `native:plugin:register` and confirm with `native:plugin:list`.

---

## Usage (SuperNative — the v4 way)

Call the facade from a `NativeComponent`, then handle the result event.

```php
use Native\Mobile\Attributes\On;
use Native\Mobile\Edge\NativeComponent;
use Vipertecpro\ImageCropper\Events\CropCancelled;
use Vipertecpro\ImageCropper\Events\ImageCropped;
use Vipertecpro\ImageCropper\Facades\ImageCropper;

class Avatar extends NativeComponent
{
    public ?string $photo = null;   // an existing image path (e.g. from the camera plugin)
    public ?string $cropped = null;

    public function crop(): void
    {
        ImageCropper::open($this->photo, ['preset' => 'profile']); // circular avatar
    }

    #[On(ImageCropped::class)]
    public function onCropped(string $path): void
    {
        $this->cropped = $path;   // a brand-new cropped JPEG on disk
    }

    #[On(CropCancelled::class)]
    public function onCancelled(): void
    {
        // user backed out — nothing produced
    }
}
```

Display the result with `native:image`, e.g. a round avatar:

```blade
<native:image :src="$cropped" :fit="2" class="w-[96] h-[96] rounded-full" />
```

### API

```php
ImageCropper::open(string $path, array $options = []);
```

The crop experience is **configurable** so one plugin covers many use cases.

| Option | Values | Default |
|---|---|---|
| `preset` | `profile` (circle), `square`, `portrait`, `landscape`, `cover`, `banner`, `story` | — |
| `shape` | `circle` \| `rect` (overrides the preset) | `rect` |
| `aspectRatio` | width / height, e.g. `16/9` (overrides the preset) | `1.0` |
| `tools` | any of `['zoom', 'rotate']` — only these fine-tune rulers show | both |
| `outputSize` | longest edge of the output, px | `1024` |
| `id` | correlation id echoed back on the event | `null` |

```php
ImageCropper::open($path, ['preset' => 'profile']);                       // round avatar
ImageCropper::open($path, ['preset' => 'cover']);                         // wide banner
ImageCropper::open($path, ['shape' => 'rect', 'aspectRatio' => 3.0, 'tools' => ['zoom']]);
```

The native screen is a small editor with three modes, chosen from the bottom bar
(`Cancel · Crop / Adjust / Filter · Done`):

- **Crop** — drag / pinch-zoom / rotate behind a circle/rect mask, a live preset
  selector (Profile → circle, 16:9, Cover, Banner, Story…) and draggable
  **Zoom / Rotate** rulers.
- **Adjust** — **Brightness / Contrast / Saturation** via draggable rulers, live.
- **Filter** — one-tap presets (Original / Vivid / Mono / Noir / Soft / Punch).

Colour changes preview live (SwiftUI modifiers / Compose `ColorMatrix`) and are
**baked into the output** on Done (CoreImage `CIColorControls` / Android
`ColorMatrix`). **Cancel** shows a "Discard Changes" confirmation. Circle crops
are written as transparent PNGs.

Restrict or hide the in-screen preset selector:

```php
ImageCropper::open($path, ['presets' => ['square', 'landscape']]); // only these two
ImageCropper::open($path, ['presets' => []]);                       // hide it, lock the shape
```

| Event | Payload | Fired when |
|---|---|---|
| `Vipertecpro\ImageCropper\Events\ImageCropped` | `string $path`, `?string $id` | The user confirms — `$path` is the new cropped JPEG. |
| `Vipertecpro\ImageCropper\Events\CropCancelled` | `?string $id` | The user cancels, or the source couldn't be read. |

Pass an `$id` when several crops could be in flight, to correlate the event.

### Usage (legacy web-view apps)

A JS wrapper is provided in `resources/js/imageCropper.js`. Because the result is
async, subscribe to the native events with the `#nativephp` `On()` helper — see the
file's header for an example.

---

## How it works

```
PHP  ImageCropper::open(path, aspectRatio, outputSize, id)
  └─ nativephp_call("ImageCropper.Open", {...})           ← synchronous bridge
        └─ Native  ImageCropperFunctions.Open.execute()
              ├─ present a full-screen crop UI over the current screen
              │     • iOS:     UIHostingController → SwiftUI CropView
              │     • Android: ComposeView overlay → CropScreen
              ├─ gestures (all simultaneous):
              │     • iOS:     DragGesture + MagnificationGesture + RotationGesture
              │     • Android: detectTransformGestures (pan + zoom + rotation)
              ├─ on "Done": render the crop region to a new JPEG (Core Graphics / Canvas+Matrix)
              └─ dispatch  ImageCropped { path }   (or CropCancelled)
PHP  #[On(ImageCropped::class)] handler receives the path
```

**The crop geometry** (identical on both platforms): the image-transform anchor and
the crop frame share the same centre, so a source pixel `p` (measured from the image
centre) lands in output space at

```
out = outputCentre + k·offset + (k · userScale · fitScale) · Rotate(θ) · p
```

where `k = outputWidth / cropFrameWidth` maps screen points to output pixels,
`fitScale` fits the whole image into the container at zoom = 1, and `offset`/`θ`/`userScale`
come from the gestures. That single affine map is set up once and the image is drawn
through it — the on-screen preview uses the exact same math, so it's WYSIWYG.

### Files

```
src/ImageCropper.php                 PHP facade target — builds the bridge call
src/Facades/ImageCropper.php         ImageCropper facade
src/Events/ImageCropped.php          success event (path, id)
src/Events/CropCancelled.php         cancel event (id)
resources/ios/ImageCropperFunctions.swift     SwiftUI crop view + Core Graphics renderer
resources/android/ImageCropperFunctions.kt    Compose crop view + Canvas/Matrix renderer
resources/js/imageCropper.js         JS bridge for legacy web-view apps
nativephp.json                       manifest: bridge_functions + events
```

---

## Extending it

- **Aspect ratio** is a parameter today. To offer an aspect picker inside the crop
  screen, add buttons that mutate the frame size (`viewport`) — the renderer already
  keys off it.
- **Draggable crop-rect corners** (resize the frame itself, not just move the image):
  add corner hit-testing + drag handlers that resize `viewport`; the render math is
  unchanged because it's driven by `viewport`.
- **Filters / adjustments**: apply a `CIFilter` (iOS) or `ColorMatrix` (Android) to the
  bitmap in the renderer before writing the file.

---

## Publishing to the NativePHP plugin marketplace

This package is published under the `vipertecpro/image-cropper` vendor
(`Vipertecpro\ImageCropper` PHP namespace, `com.vipertecpro.plugins.image_cropper`
Android package). To make it publicly installable:

1. Add `resources/icon.png` (a square logo) and, ideally, `screenshots`.
2. Validate: `php artisan native:plugin:validate` → must be **OK** with zero errors.
3. Push to the public Git repo (`https://github.com/vipertecpro/image-cropper`) and tag a
   release: `git tag v1.0.0 && git push --tags`.
4. **Packagist** — submit the repo at <https://packagist.org/packages/submit> so anyone
   can `composer require vipertecpro/image-cropper`.
5. **NativePHP marketplace** — submit at <https://nativephp.com/plugins> for discovery.
6. Test on **physical** iOS and Android devices; provide TestFlight / Play test links.

See the checklist in NativePHP's *Creating Plugins* docs.

---

## Caveats (please verify on-device)

This crop view is hand-written and was authored without a device compile, so treat the
first build as a calibration pass:

- **Rotation direction / sign.** SwiftUI and Compose report clockwise-positive rotation,
  and the renderers use it directly. If a rotated crop comes out mirrored or turned the
  wrong way, negate the rotation in the renderer (`transform.rotation` in Swift /
  `state.rotationDeg` in Kotlin).
- **Very large images.** Cropping happens on a background thread; for enormous source
  images consider downsampling on decode.
- Report anything off and it's a small, localized fix in the two renderer functions.

## License

MIT
