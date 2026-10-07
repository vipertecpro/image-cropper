# Image Cropper for NativePHP — a native image cropper & editor

Open a **fully native** crop & edit screen from PHP — the user drags, pinch-zooms
and two-finger-rotates the image behind a crop frame, then you get a **real
cropped file** back. Written in SwiftUI (iOS) and Jetpack Compose (Android), with
one simple PHP API and **zero third-party native libraries**.

## Features

- **Freehand crop** — 2D drag, pinch-zoom and two-finger rotate, all at once
- ⭕ **Shapes & presets** — circle or rectangle; Profile / Square / Portrait / 16:9 / Cover / Banner / Story, switchable live in-screen
- **Adjust & filter** — brightness / contrast / saturation and one-tap filters, baked into the output
- **Configurable** — enable only the modes/tools you need (crop-only, adjust-only, filter-only, …)
- **Theme-aware** — follows the system light / dark theme
- **Zero dependencies** — no third-party native libraries, no permissions, no network
- **iOS + Android** behind one PHP API

## Requirements

- PHP 8.4+
- NativePHP Mobile v3 or v4 (`nativephp/mobile: ^3.0|^4.0`) — tested on iOS and
  Android against 4.6
- iOS 15+ / Android API 26+

## Installation

```bash
composer require vipertecpro/image-cropper
php artisan vendor:publish --tag=nativephp-plugins-provider   # once per app
php artisan native:plugin:register vipertecpro/image-cropper
php artisan native:plugin:list       # verify "ImageCropper" + "ImageCropper.Open" appear
php artisan native:run ios           # or: android — rebuild so the native code compiles in
```

> Requiring with Composer is **not** enough — an unregistered plugin does
> nothing. Always run `native:plugin:register` and confirm with
> `native:plugin:list`.

## Usage

Call the facade from a `NativeComponent`, then handle the result event.

```php
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
        ImageCropper::open($this->photo, ['preset' => 'profile']); // circular avatar
    }

    #[On(ImageCropped::class)]
    public function onCropped(string $path): void
    {
        $this->cropped = $path;      // a brand-new cropped file on disk
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

> **On NativePHP Mobile v3**, the `#[On]` attribute isn't available — listen for
> the events with `#[OnNative(...)]` (from `Native\Mobile\Attributes\OnNative`)
> instead. Everything else is identical; the plugin itself is unchanged across v3
> and v4.

### Getting an image to crop

The plugin's input is a **file path** to an existing image **or an http(s)
URL** — it does not capture or pick photos itself, so it has no hard dependency
on any camera plugin. Any source works: the filesystem, a bundled asset, a
picker of your choice, or a remote image.

```php
// Crop an image that lives on your CDN/API — the plugin downloads it
// natively (themed loading screen with Cancel), then opens the editor:
ImageCropper::open($user->avatar_url, ['preset' => 'profile']);   // an http(s) URL
```

Only **croppable image formats** are accepted (`jpg`, `jpeg`, `png`, `gif`,
`webp`, `bmp`, `heic`, `heif`, `avif`): a source with any other extension
throws an `InvalidArgumentException` immediately, and the native side
additionally verifies the actual **content** decodes as an image (extensions
can lie) — undecodable content fires `CropCancelled`, as do download failures.

If you need a picker, `nativephp/mobile-camera`
is convenient (listed under `suggest`, not `require`). Install and register it
separately, then hand its result path to `ImageCropper::open()`.

### API

```php
ImageCropper::open(string $pathOrUrl, array $options = []): void;
```

The crop experience is **configurable** so one plugin covers many use cases.

| Option | Values | Default |
|---|---|---|
| `preset` | `profile` (circle), `square`, `portrait`, `landscape`, `cover`, `banner`, `story` | — |
| `shape` | `circle` \| `rect` (overrides the preset) | `rect` |
| `aspectRatio` | width / height, e.g. `16/9` (overrides the preset) | `1.0` |
| `tools` | any of `['zoom', 'rotate']` — which crop fine-tune rulers show | both |
| `modes` | any of `['crop', 'adjust', 'filter']` — editor modes offered; opens on the first | all three |
| `presets` | preset keys offered in the in-screen selector; `[]` hides it & locks the crop | all |
| `outputSize` | longest edge of the output, px | `1024` |
| `theme` | hex colors to match YOUR app — `background`, `text`, `accent` (Done), `highlight` (active states); omitted keys keep the system-adaptive default | system light/dark |
| `id` | correlation id echoed back on the event | `null` |

```php
ImageCropper::open($path, ['preset' => 'profile']);   // round avatar
ImageCropper::open($path, ['preset' => 'cover']);     // wide banner
ImageCropper::open($path, ['shape' => 'rect', 'aspectRatio' => 3.0, 'tools' => ['zoom']]);

// A bare, locked cropper — no preset switching, no colour editing:
ImageCropper::open($path, ['preset' => 'profile', 'presets' => [], 'modes' => ['crop']]);

// No crop at all — operate on the WHOLE image and export the full photo:
ImageCropper::open($path, ['modes' => ['adjust']]);   // colour adjustments only
ImageCropper::open($path, ['modes' => ['filter']]);   // one-tap filters only

// Render the editor in YOUR app's theme (identical on iOS and Android):
ImageCropper::open($path, [
    'preset' => 'profile',
    'theme' => [
        'background' => '#121417',   // editor background
        'text' => '#FFFFFF',         // labels & inactive icons
        'accent' => '#C2410C',       // the Done button
        'highlight' => '#C2410C',    // selected preset/filter, ruler value & fill
    ],
]);
```

When `modes` omits `crop`, the editor drops the crop frame entirely, shows the
whole image, and exports the full photo (longest edge = `outputSize`) with the
colour baked in.

### Events

| Event | Payload | Fired when |
|---|---|---|
| `Vipertecpro\ImageCropper\Events\ImageCropped` | `string $path`, `?string $id` | The user confirms — `$path` is the new cropped file. |
| `Vipertecpro\ImageCropper\Events\CropCancelled` | `?string $id` | The user cancels, or the source couldn't be read. |

Pass an `id` option when several crops could be in flight, to correlate the event.

### Legacy web-view apps

A JS bridge is shipped at `resources/js/imageCropper.js`. Because the result is
async, subscribe to the native events with the `#nativephp` `On()` helper — see
the file header for an example.

## Demo app

A full sample NativePHP app showcases the plugin with **five ready-made examples**
(full studio, locked profile avatar, locked cover, adjust-only, filter-only) —
each a tiny `NativeComponent` you can copy from:

It lives in the **vipertecpro/supernativephp-image-manipulation** repository on
GitHub. Clone it, then:

```bash
cd supernativephp-image-manipulation
composer install
php artisan native:run ios   # or: android
```

## Contributing

Issues and pull requests are welcome. See the `CONTRIBUTING.md` file included with the package for
local setup, the project layout, how it works, and how to extend the crop editor.

## Changelog

See the `CHANGELOG.md` file included with the package for the full version history.

## Licence

MIT — see the `LICENSE` file included with the package.

## More free plugins for NativePHP

The Image Cropper has free companions, each installed with Composer and made
the same way (hand-written Swift and Kotlin, one PHP API):

- **Photo Kit** — save a cropped image to the photo library, compress and resize it for upload, fix its EXIF orientation (vipertecpro/photo-kit)
- **Message Composer** — native email and SMS composers with attachments, e.g. to send the cropped photo (vipertecpro/message-composer)
- **OTP Autofill** — a one-time-code field with SMS autofill (vipertecpro/otp-autofill)
- **Consent** — the tracking-permission prompt and a privacy-choices store (vipertecpro/consent)
- **App Analytics & Remote Config** — analytics events and feature flags, works with Firebase (vipertecpro/app-analytics)

vipertecpro is an independent developer. NativePHP, Laravel, Apple, Google, Firebase and other names are trademarks of their respective owners; this package is not affiliated with or endorsed by them. iOS and Apple are trademarks of Apple Inc. Android, Google Play and Firebase are trademarks of Google LLC.

Image Cropper is a free plugin from vipertecpro.com, home of the paid plugins for NativePHP Mobile: Rich-Text Editor, Onboarding & Tours, Health Data and Native Charts.
