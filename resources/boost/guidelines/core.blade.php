## vipertecpro/image-cropper

A NativePHP Mobile plugin that opens a **fully native** image crop & edit screen
(SwiftUI on iOS, Jetpack Compose on Android) and returns a **new cropped file**
to PHP via an event. Works with NativePHP Mobile v3 and v4.

### What it does / does not do

- It edits an image the app **already has a file path for** — it does NOT capture
  or pick photos. Feed it any path (a bundled asset, a download, or the result of
  a camera/gallery picker such as `nativephp/mobile-camera`).
- Input: a single absolute file path. Output: a brand-new cropped JPEG (or a
  transparent PNG for circle crops) — the source file is never modified.
- The call is fire-and-forget: `open()` returns `void`; the result arrives later
  as an event.

### The one method

`ImageCropper::open(string $path, array $options = []): void`

`$options` keys (all optional):

- `preset`: `profile` (circle 1:1), `square`, `portrait`, `landscape` (16:9), `cover`, `banner`, `story`. Sets shape + aspect ratio.
- `shape`: `circle` | `rect` — overrides the preset's shape.
- `aspectRatio`: float, width / height, e.g. `16/9` — overrides the preset's ratio.
- `tools`: subset of `['zoom', 'rotate']` — which crop fine-tune rulers show.
- `modes`: subset of `['crop', 'adjust', 'filter']` — which editor modes are offered; the editor opens on the first. Omit `crop` to edit the whole photo with no crop frame.
- `presets`: list of preset keys offered in the in-screen selector; `[]` hides it and locks the crop shape.
- `outputSize`: int, longest edge of the output in px (default `1024`).
- `theme`: hex colors so the editor matches the HOST APP's look instead of its own — keys `background`, `text`, `accent` (Done button), `highlight` (active states). All optional; omitted keys keep the plugin's system-adaptive light/dark default. Identical rendering on iOS and Android.
- `id`: string echoed back on the result event, to correlate concurrent crops.

### Usage (SuperNative / NativeComponent)

Call the facade from a `NativeComponent`, then handle the result with `#[On]`.

@verbatim
<code-snippet name="Crop a profile avatar in a NativeComponent" lang="php">
use Native\Mobile\Attributes\On;
use Native\Mobile\Edge\NativeComponent;
use Vipertecpro\ImageCropper\Events\CropCancelled;
use Vipertecpro\ImageCropper\Events\ImageCropped;
use Vipertecpro\ImageCropper\Facades\ImageCropper;

class Avatar extends NativeComponent
{
    public ?string $photo = null;    // an existing image path
    public ?string $cropped = null;

    public function crop(): void
    {
        ImageCropper::open($this->photo, ['preset' => 'profile']);
    }

    #[On(ImageCropped::class)]
    public function onCropped(string $path): void
    {
        $this->cropped = $path;      // a new cropped file on disk
    }

    #[On(CropCancelled::class)]
    public function onCancelled(): void
    {
        // user backed out — nothing produced
    }
}
</code-snippet>
@endverbatim

### Events

Both events live under `Vipertecpro\ImageCropper\Events`. Listen with the
`#[On(EventClass::class)]` attribute on a `NativeComponent` method.

- `ImageCropped` — payload `string $path`, `?string $id`. Fired when the user taps Done; `$path` is the new cropped file.
- `CropCancelled` — payload `?string $id`. Fired when the user cancels or the source could not be read.

On NativePHP Mobile **v3**, use `#[OnNative(...)]` (from
`Native\Mobile\Attributes\OnNative`) instead of `#[On]` — the `#[On]` attribute
is v4-only. The plugin itself is unchanged across v3 and v4.

### Common configurations

@verbatim
<code-snippet name="Configuring the crop editor" lang="php">
// Round avatar, locked — no preset switching, crop only (no adjust/filter):
ImageCropper::open($path, ['preset' => 'profile', 'presets' => [], 'modes' => ['crop']]);

// Wide cover/banner:
ImageCropper::open($path, ['preset' => 'cover']);

// Fixed 3:1 rect, only the zoom ruler:
ImageCropper::open($path, ['shape' => 'rect', 'aspectRatio' => 3.0, 'tools' => ['zoom']]);

// No crop at all — colour-adjust the whole photo and export it full-size:
ImageCropper::open($path, ['modes' => ['adjust']]);

// One-tap filters only, whole photo:
ImageCropper::open($path, ['modes' => ['filter']]);

// Match the host app's theme (pass your app's own colors):
ImageCropper::open($path, [
    'theme' => ['background' => '#121417', 'text' => '#FFFFFF', 'accent' => '#C2410C', 'highlight' => '#C2410C'],
]);
</code-snippet>
@endverbatim

### Getting an image path (optional camera/gallery)

The plugin has no hard dependency on a picker. If you need one,
`nativephp/mobile-camera` is convenient — install and register it separately,
then hand its result path to `ImageCropper::open()`.

@verbatim
<code-snippet name="Pick then crop" lang="php">
use Native\Mobile\Facades\Camera;

Camera::pickImages('images', false);   // fires MediaSelected -> $files[0]
// then, in your MediaSelected handler:
ImageCropper::open($files[0], ['preset' => 'profile']);
</code-snippet>
@endverbatim

### Installation & registration

Requiring with Composer is not enough — the plugin must be registered, or it does
nothing. Then rebuild so the native code compiles in.

@verbatim
<code-snippet name="Install & register the plugin" lang="bash">
composer require vipertecpro/image-cropper
php artisan vendor:publish --tag=nativephp-plugins-provider   # once per app
php artisan native:plugin:register vipertecpro/image-cropper
php artisan native:plugin:list      # verify "ImageCropper" + "ImageCropper.Open" appear
php artisan native:run ios          # or: android  — rebuild to compile native code
</code-snippet>
@endverbatim

### Displaying the result

@verbatim
<code-snippet name="Show a cropped avatar" lang="blade">
<native:image :src="$cropped" :fit="2" class="w-[96] h-[96] rounded-full" />
</code-snippet>
@endverbatim

### Legacy web-view apps

A JS bridge is shipped at `resources/js/imageCropper.js`. Because the result is
async, subscribe to the native events with the `#nativephp` `On()` helper — see
the file header for an example. For SuperNative/native apps, prefer the
`NativeComponent` + `#[On]` approach above.
