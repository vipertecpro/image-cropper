<?php

namespace Vipertecpro\ImageCropper;

use Vipertecpro\ImageCropper\Events\CropCancelled;
use Vipertecpro\ImageCropper\Events\ImageCropped;

/**
 * PHP entry point for the native image cropper.
 *
 * {@see open()} hands a source image to the native side, which presents a
 * full-screen, config-driven crop UI (SwiftUI on iOS, Jetpack Compose on
 * Android). The user positions the image FREEHAND — drag, pinch-zoom, rotate —
 * behind a crop mask, and fine-tunes with draggable Zoom / Rotate rulers. On
 * confirm the cropped region is rendered to a NEW file and reported back
 * **asynchronously via an event**.
 *
 * The crop experience is configurable so one plugin covers many use cases —
 * profile avatars (circular), banners, covers, etc. — rather than a
 * one-size-fits-all cropper.
 *
 *     use Vipertecpro\ImageCropper\Facades\ImageCropper;
 *
 *     ImageCropper::open($path, ['preset' => 'profile']);            // circular avatar
 *     ImageCropper::open($path, ['preset' => 'cover']);              // 16:9 banner
 *     ImageCropper::open($path, ['shape' => 'rect', 'aspectRatio' => 3.0, 'tools' => ['zoom']]);
 *
 * Handle the result in a NativeComponent with #[On(ImageCropped::class)] /
 * #[On(CropCancelled::class)].
 */
class ImageCropper
{
    /**
     * Built-in crop presets → shape + aspect ratio (width / height).
     * `aspectRatio` of 0.0 means "free" (unconstrained).
     *
     * @var array<string, array{shape: string, aspectRatio: float}>
     */
    public const PRESETS = [
        'profile' => ['shape' => 'circle', 'aspectRatio' => 1.0],   // round avatar
        'square' => ['shape' => 'rect', 'aspectRatio' => 1.0],      // 1:1 post
        'portrait' => ['shape' => 'rect', 'aspectRatio' => 0.8],    // 4:5
        'landscape' => ['shape' => 'rect', 'aspectRatio' => 1.7778], // 16:9
        'cover' => ['shape' => 'rect', 'aspectRatio' => 2.7],       // wide cover / banner
        'banner' => ['shape' => 'rect', 'aspectRatio' => 4.0],      // LinkedIn-style banner
        'story' => ['shape' => 'rect', 'aspectRatio' => 0.5625],    // 9:16
    ];

    /** Human labels for the presets, shown in the native crop screen's selector. */
    public const PRESET_LABELS = [
        'profile' => 'Profile',
        'square' => 'Square',
        'portrait' => 'Portrait',
        'landscape' => '16:9',
        'cover' => 'Cover',
        'banner' => 'Banner',
        'story' => 'Story',
    ];

    /** Crop-screen tools that can be toggled on/off per call. */
    public const AVAILABLE_TOOLS = ['zoom', 'rotate'];

    /**
     * File extensions the editor accepts — image formats the native platforms
     * can decode and crop. This is the EARLY, developer-facing gate: a source
     * whose extension is not in this list is rejected with an exception before
     * the bridge is ever called. The native side then performs the REAL check
     * by decoding the bytes (extensions can lie; decoding can't) and fires
     * CropCancelled if the content isn't a decodable image.
     */
    public const CROPPABLE_EXTENSIONS = ['jpg', 'jpeg', 'png', 'gif', 'webp', 'bmp', 'heic', 'heif', 'avif'];

    /**
     * Editor modes that can be enabled per call. Pass a subset to strip the UI
     * down — e.g. `['crop']` for a bare cropper with no colour editing. The
     * first entry is the mode the editor opens on.
     */
    public const AVAILABLE_MODES = ['crop', 'adjust', 'filter'];

    /**
     * Theme keys the editor accepts, all optional hex colors (#RGB / #RRGGBB /
     * #RRGGBBAA). Any key omitted falls back to the editor's built-in
     * system-adaptive light/dark default — pass only what you want to override.
     *
     *  - background: editor screen background
     *  - text:       titles, labels and inactive icons
     *  - accent:     the Done button
     *  - highlight:  active states (selected preset/filter, ruler value & fill)
     */
    public const THEME_KEYS = ['background', 'text', 'accent', 'highlight'];

    /**
     * Open the native crop screen.
     *
     * @param  string  $path  Absolute path to a source image on the device, OR
     *                        an http(s) URL — remote images are downloaded by
     *                        the native side (with a themed loading screen and
     *                        Cancel) before the editor opens. Only croppable
     *                        image formats are accepted; see
     *                        {@see CROPPABLE_EXTENSIONS}.
     * @param  array{
     *     preset?: string,
     *     shape?: string,
     *     aspectRatio?: float,
     *     tools?: list<string>,
     *     modes?: list<string>,
     *     presets?: list<string>,
     *     outputSize?: int,
     *     theme?: array<string, string>,
     *     id?: string|null
     * }  $options  Crop configuration. `preset` sets shape+ratio; explicit
     *              `shape`/`aspectRatio` override it. `tools` picks which crop
     *              fine-tune controls appear (zoom/rotate). `modes` picks which
     *              editor modes are available (crop/adjust/filter) — pass
     *              `['crop']` for a bare cropper. `presets` is the list of
     *              switchable presets offered in-screen (`[]` locks the crop).
     *              `theme` recolours the editor to match YOUR app (see
     *              {@see THEME_KEYS}) — omitted keys keep the system-adaptive
     *              defaults.
     *
     * Fires {@see ImageCropped} on success and
     * {@see CropCancelled} on cancel (including when a remote download fails
     * or the source bytes don't decode as an image).
     *
     * @throws \InvalidArgumentException When the source is not a croppable
     *                                   image format or uses an unsupported
     *                                   URL scheme.
     */
    public function open(string $path, array $options = []): void
    {
        $this->assertCroppableSource($path);

        if (! function_exists('nativephp_call')) {
            return;
        }

        nativephp_call('ImageCropper.Open', json_encode($this->resolveConfig($path, $options)));
    }

    /**
     * Reject sources that can never be cropped, LOUDLY, before touching the
     * bridge — a wrong-format path is a developer error, not a user cancel.
     *
     *  - http(s) URLs are allowed; when the URL path carries an extension it
     *    must be croppable. Extensionless URLs (e.g. picsum.photos/1200) pass
     *    here and are content-validated by the native decode after download.
     *  - Any other "scheme://" source is unsupported.
     *  - Local paths with an extension must use a croppable one. Extensionless
     *    local files pass here and are validated by the native decode.
     */
    protected function assertCroppableSource(string $source): void
    {
        if (preg_match('/^([a-z][a-z0-9+.-]*):\/\//i', $source, $m) === 1) {
            if (! in_array(strtolower($m[1]), ['http', 'https'], true)) {
                throw new \InvalidArgumentException(
                    "ImageCropper only supports local paths and http(s) URLs — got scheme \"{$m[1]}\"."
                );
            }

            $urlPath = (string) parse_url($source, PHP_URL_PATH);
            $extension = strtolower(pathinfo($urlPath, PATHINFO_EXTENSION));
        } else {
            $extension = strtolower(pathinfo($source, PATHINFO_EXTENSION));
        }

        if ($extension !== '' && ! in_array($extension, self::CROPPABLE_EXTENSIONS, true)) {
            throw new \InvalidArgumentException(
                "ImageCropper cannot crop \".{$extension}\" files — croppable formats are: "
                .implode(', ', self::CROPPABLE_EXTENSIONS).'.'
            );
        }
    }

    /**
     * Merge caller options with the chosen preset and sane defaults into the
     * flat config the native side consumes.
     *
     * @return array{path: string, shape: string, aspectRatio: float, tools: list<string>, modes: list<string>, presets: list<array{key: string, label: string, shape: string, aspectRatio: float}>, outputSize: int, id: string|null}
     */
    protected function resolveConfig(string $path, array $options): array
    {
        $preset = self::PRESETS[$options['preset'] ?? ''] ?? ['shape' => 'rect', 'aspectRatio' => 1.0];

        $tools = array_values(array_intersect(
            $options['tools'] ?? self::AVAILABLE_TOOLS,
            self::AVAILABLE_TOOLS,
        ));

        $modes = array_values(array_intersect(
            $options['modes'] ?? self::AVAILABLE_MODES,
            self::AVAILABLE_MODES,
        ));

        return [
            'path' => $path,
            'shape' => $options['shape'] ?? $preset['shape'],
            'aspectRatio' => (float) ($options['aspectRatio'] ?? $preset['aspectRatio']),
            'tools' => $tools === [] ? self::AVAILABLE_TOOLS : $tools,
            'modes' => $modes === [] ? self::AVAILABLE_MODES : $modes,
            // The presets offered in the native screen's selector (switchable live).
            // Pass `presets => []` to hide the selector and lock the crop shape.
            'presets' => $this->resolvePresets($options['presets'] ?? array_keys(self::PRESETS)),
            'outputSize' => (int) ($options['outputSize'] ?? 1024),
            'theme' => $this->resolveTheme($options['theme'] ?? []),
            'id' => $options['id'] ?? null,
        ];
    }

    /**
     * Keep only known theme keys holding valid hex colors, normalised with a
     * leading '#'. Unknown keys and malformed values are dropped — the native
     * side falls back to its adaptive default for anything missing.
     *
     * @param  array<string, string>  $theme
     * @return array<string, string>
     */
    protected function resolveTheme(array $theme): array
    {
        $clean = [];

        foreach (self::THEME_KEYS as $key) {
            $value = $theme[$key] ?? null;

            if (is_string($value) && preg_match('/^#?([0-9a-fA-F]{3}|[0-9a-fA-F]{6}|[0-9a-fA-F]{8})$/', $value, $m)) {
                $clean[$key] = '#'.$m[1];
            }
        }

        return $clean;
    }

    /**
     * Expand a list of preset keys into full descriptors for the native selector.
     *
     * @param  list<string>  $keys
     * @return list<array{key: string, label: string, shape: string, aspectRatio: float}>
     */
    protected function resolvePresets(array $keys): array
    {
        $presets = [];

        foreach ($keys as $key) {
            if (isset(self::PRESETS[$key])) {
                $presets[] = [
                    'key' => $key,
                    'label' => self::PRESET_LABELS[$key] ?? ucfirst($key),
                    'shape' => self::PRESETS[$key]['shape'],
                    'aspectRatio' => (float) self::PRESETS[$key]['aspectRatio'],
                ];
            }
        }

        return $presets;
    }
}
