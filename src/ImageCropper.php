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
     * Editor modes that can be enabled per call. Pass a subset to strip the UI
     * down — e.g. `['crop']` for a bare cropper with no colour editing. The
     * first entry is the mode the editor opens on.
     */
    public const AVAILABLE_MODES = ['crop', 'adjust', 'filter'];

    /**
     * Open the native crop screen.
     *
     * @param  string  $path  Absolute path to the source image (jpg/png).
     * @param  array{
     *     preset?: string,
     *     shape?: string,
     *     aspectRatio?: float,
     *     tools?: list<string>,
     *     modes?: list<string>,
     *     presets?: list<string>,
     *     outputSize?: int,
     *     id?: string|null
     * }  $options  Crop configuration. `preset` sets shape+ratio; explicit
     *              `shape`/`aspectRatio` override it. `tools` picks which crop
     *              fine-tune controls appear (zoom/rotate). `modes` picks which
     *              editor modes are available (crop/adjust/filter) — pass
     *              `['crop']` for a bare cropper. `presets` is the list of
     *              switchable presets offered in-screen (`[]` locks the crop).
     *
     * Fires {@see ImageCropped} on success and
     * {@see CropCancelled} on cancel.
     */
    public function open(string $path, array $options = []): void
    {
        if (! function_exists('nativephp_call')) {
            return;
        }

        nativephp_call('ImageCropper.Open', json_encode($this->resolveConfig($path, $options)));
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
            'id' => $options['id'] ?? null,
        ];
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
