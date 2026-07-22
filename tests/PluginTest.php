<?php

use Vipertecpro\ImageCropper\ImageCropper;

/**
 * Plugin validation tests for ImageCropper.
 *
 * Run with: ./vendor/bin/pest
 */
beforeEach(function () {
    $this->pluginPath = dirname(__DIR__);
    $this->manifestPath = $this->pluginPath.'/nativephp.json';
});

describe('Plugin Manifest', function () {
    it('has a valid nativephp.json file', function () {
        expect(file_exists($this->manifestPath))->toBeTrue();

        $content = file_get_contents($this->manifestPath);
        $manifest = json_decode($content, true);

        expect(json_last_error())->toBe(JSON_ERROR_NONE);
    });

    it('has required fields', function () {
        $manifest = json_decode(file_get_contents($this->manifestPath), true);

        expect($manifest)->toHaveKeys(['name', 'namespace', 'bridge_functions']);
        expect($manifest['name'])->toBe('vipertecpro/image-cropper');
        expect($manifest['namespace'])->toBe('ImageCropper');
    });

    it('has valid bridge functions', function () {
        $manifest = json_decode(file_get_contents($this->manifestPath), true);

        expect($manifest['bridge_functions'])->toBeArray();

        foreach ($manifest['bridge_functions'] as $function) {
            expect($function)->toHaveKeys(['name']);
            expect(isset($function['android']) || isset($function['ios']))->toBeTrue();
        }
    });

    it('has valid marketplace metadata', function () {
        $manifest = json_decode(file_get_contents($this->manifestPath), true);

        // Optional but recommended for marketplace
        if (isset($manifest['keywords'])) {
            expect($manifest['keywords'])->toBeArray();
        }

        if (isset($manifest['category'])) {
            expect($manifest['category'])->toBeString();
        }

        if (isset($manifest['platforms'])) {
            expect($manifest['platforms'])->toBeArray();
            foreach ($manifest['platforms'] as $platform) {
                expect($platform)->toBeIn(['android', 'ios']);
            }
        }
    });
});

describe('Native Code', function () {
    it('has Android Kotlin file', function () {
        $kotlinFile = $this->pluginPath.'/resources/android/ImageCropperFunctions.kt';

        expect(file_exists($kotlinFile))->toBeTrue();

        $content = file_get_contents($kotlinFile);
        expect($content)->toContain('package com.vipertecpro.plugins.image_cropper');
        expect($content)->toContain('object ImageCropperFunctions');
        expect($content)->toContain('BridgeFunction');
    });

    it('has iOS Swift file', function () {
        $swiftFile = $this->pluginPath.'/resources/ios/ImageCropperFunctions.swift';

        expect(file_exists($swiftFile))->toBeTrue();

        $content = file_get_contents($swiftFile);
        expect($content)->toContain('enum ImageCropperFunctions');
        expect($content)->toContain('BridgeFunction');
    });

    it('has matching bridge function classes in native code', function () {
        $manifest = json_decode(file_get_contents($this->manifestPath), true);

        $kotlinFile = $this->pluginPath.'/resources/android/ImageCropperFunctions.kt';
        $swiftFile = $this->pluginPath.'/resources/ios/ImageCropperFunctions.swift';

        $kotlinContent = file_get_contents($kotlinFile);
        $swiftContent = file_get_contents($swiftFile);

        foreach ($manifest['bridge_functions'] as $function) {
            // Extract class name from the function reference
            if (isset($function['android'])) {
                $parts = explode('.', $function['android']);
                $className = end($parts);
                expect($kotlinContent)->toContain("class {$className}");
            }

            if (isset($function['ios'])) {
                $parts = explode('.', $function['ios']);
                $className = end($parts);
                expect($swiftContent)->toContain("class {$className}");
            }
        }
    });
});

describe('PHP Classes', function () {
    it('has service provider', function () {
        $file = $this->pluginPath.'/src/ImageCropperServiceProvider.php';
        expect(file_exists($file))->toBeTrue();

        $content = file_get_contents($file);
        expect($content)->toContain('namespace Vipertecpro\ImageCropper');
        expect($content)->toContain('class ImageCropperServiceProvider');
    });

    it('has facade', function () {
        $file = $this->pluginPath.'/src/Facades/ImageCropper.php';
        expect(file_exists($file))->toBeTrue();

        $content = file_get_contents($file);
        expect($content)->toContain('namespace Vipertecpro\ImageCropper\Facades');
        expect($content)->toContain('class ImageCropper extends Facade');
    });

    it('has main implementation class', function () {
        $file = $this->pluginPath.'/src/ImageCropper.php';
        expect(file_exists($file))->toBeTrue();

        $content = file_get_contents($file);
        expect($content)->toContain('namespace Vipertecpro\ImageCropper');
        expect($content)->toContain('class ImageCropper');
    });
});

describe('Cropper API', function () {
    it('declares the ImageCropped and CropCancelled events', function () {
        $manifest = json_decode(file_get_contents($this->manifestPath), true);

        expect($manifest['events'])->toContain('Vipertecpro\\ImageCropper\\Events\\ImageCropped');
        expect($manifest['events'])->toContain('Vipertecpro\\ImageCropper\\Events\\CropCancelled');

        expect(file_exists($this->pluginPath.'/src/Events/ImageCropped.php'))->toBeTrue();
        expect(file_exists($this->pluginPath.'/src/Events/CropCancelled.php'))->toBeTrue();
    });

    it('exposes an Open bridge function wired to native crop entrypoints', function () {
        $manifest = json_decode(file_get_contents($this->manifestPath), true);
        $names = array_column($manifest['bridge_functions'], 'name');

        expect($names)->toContain('ImageCropper.Open');

        $swift = file_get_contents($this->pluginPath.'/resources/ios/ImageCropperFunctions.swift');
        $kotlin = file_get_contents($this->pluginPath.'/resources/android/ImageCropperFunctions.kt');

        // The crop pipeline is present on both platforms.
        expect($swift)->toContain('CropRenderer');
        expect($swift)->toContain('MagnificationGesture');
        expect($swift)->toContain('RotationGesture');
        expect($kotlin)->toContain('CropRenderer');
        expect($kotlin)->toContain('detectTransformGestures');

        // The pan clamp is rotation-aware on both platforms (no black gap when rotated).
        expect($swift)->toContain('clampOffset');
        expect($kotlin)->toContain('clampOffset');
    });

    it('documents open() on the facade', function () {
        $facade = file_get_contents($this->pluginPath.'/src/Facades/ImageCropper.php');
        expect($facade)->toContain('static void open(');
    });

    it('resolves presets to shape + aspect ratio', function () {
        $cropper = new ImageCropper;
        $resolve = (new ReflectionMethod($cropper, 'resolveConfig'));
        $resolve->setAccessible(true);

        $profile = $resolve->invoke($cropper, '/a.jpg', ['preset' => 'profile']);
        expect($profile['shape'])->toBe('circle')->and($profile['aspectRatio'])->toBe(1.0);

        $cover = $resolve->invoke($cropper, '/a.jpg', ['preset' => 'cover', 'tools' => ['zoom']]);
        expect($cover['shape'])->toBe('rect')->and($cover['tools'])->toBe(['zoom']);

        // Explicit overrides win over the preset.
        $custom = $resolve->invoke($cropper, '/a.jpg', ['preset' => 'profile', 'shape' => 'rect']);
        expect($custom['shape'])->toBe('rect');

        // Modes default to all three; a subset strips the editor down.
        expect($profile['modes'])->toBe(['crop', 'adjust', 'filter']);
        $bare = $resolve->invoke($cropper, '/a.jpg', ['modes' => ['crop'], 'presets' => []]);
        expect($bare['modes'])->toBe(['crop'])->and($bare['presets'])->toBe([]);
    });

    it('ships the configurable native crop UI (mask + ruler) on both platforms', function () {
        $swift = file_get_contents($this->pluginPath.'/resources/ios/ImageCropperFunctions.swift');
        $kotlin = file_get_contents($this->pluginPath.'/resources/android/ImageCropperFunctions.kt');

        foreach ([$swift, $kotlin] as $code) {
            expect($code)->toContain('Ruler');       // draggable ruler control
            expect($code)->toContain('CropConfig');  // config-driven
        }
        // Circular mask + discard confirmation.
        expect($swift)->toContain('circle')->and($swift)->toContain('Discard Changes');
        expect($kotlin)->toContain('addOval')->and($kotlin)->toContain('Discard Changes');

        // Live preset switching inside the native screen.
        expect($swift)->toContain('CropPreset')->and($swift)->toContain('config.presets');
        expect($kotlin)->toContain('CropPreset')->and($kotlin)->toContain('config.presets');
    });

    it('ships crop / adjust / filter modes with baked colour adjustments', function () {
        $swift = file_get_contents($this->pluginPath.'/resources/ios/ImageCropperFunctions.swift');
        $kotlin = file_get_contents($this->pluginPath.'/resources/android/ImageCropperFunctions.kt');

        // Three modes are present on both platforms.
        foreach ([$swift, $kotlin] as $code) {
            expect($code)->toContain('adjust');
            expect($code)->toContain('filter');
            expect($code)->toContain('Brightness');
            expect($code)->toContain('Contrast');
            expect($code)->toContain('Saturation');
        }
        // Colour is baked into the output, not just previewed.
        expect($swift)->toContain('CIColorControls');
        expect($kotlin)->toContain('ColorMatrix');
    });

    it('carries the switchable preset list in the resolved config', function () {
        $cropper = new ImageCropper;
        $resolve = (new ReflectionMethod($cropper, 'resolveConfig'));
        $resolve->setAccessible(true);

        $all = $resolve->invoke($cropper, '/a.jpg', ['preset' => 'profile']);
        expect($all['presets'])->toHaveCount(count(ImageCropper::PRESETS));
        expect($all['presets'][0])->toHaveKeys(['key', 'label', 'shape', 'aspectRatio']);

        // A caller can restrict which presets appear.
        $some = $resolve->invoke($cropper, '/a.jpg', ['presets' => ['square', 'landscape']]);
        expect(array_column($some['presets'], 'key'))->toBe(['square', 'landscape']);
    });
});

describe('Composer Configuration', function () {
    it('has valid composer.json', function () {
        $composerPath = $this->pluginPath.'/composer.json';
        expect(file_exists($composerPath))->toBeTrue();

        $content = file_get_contents($composerPath);
        $composer = json_decode($content, true);

        expect(json_last_error())->toBe(JSON_ERROR_NONE);
        expect($composer['type'])->toBe('nativephp-plugin');
        expect($composer['extra']['nativephp']['manifest'])->toBe('nativephp.json');
    });
});

describe('Lifecycle Hooks', function () {
    it('has valid hooks configuration', function () {
        $manifest = json_decode(file_get_contents($this->manifestPath), true);

        if (isset($manifest['hooks'])) {
            expect($manifest['hooks'])->toBeArray();

            $validHooks = ['pre_compile', 'post_compile', 'copy_assets', 'post_build'];
            foreach (array_keys($manifest['hooks']) as $hook) {
                expect($hook)->toBeIn($validHooks);
            }
        }
    });

    it('has copy_assets hook command', function () {
        $manifest = json_decode(file_get_contents($this->manifestPath), true);

        expect($manifest['hooks']['copy_assets'] ?? null)->not->toBeNull();

        $commandFile = $this->pluginPath.'/src/Commands/CopyAssetsCommand.php';
        expect(file_exists($commandFile))->toBeTrue();
    });

    it('copy_assets command extends NativePluginHookCommand', function () {
        $commandFile = $this->pluginPath.'/src/Commands/CopyAssetsCommand.php';
        $content = file_get_contents($commandFile);

        expect($content)->toContain('extends NativePluginHookCommand');
        expect($content)->toContain('use Native\Mobile\Plugins\Commands\NativePluginHookCommand');
    });

    it('copy_assets command has correct signature', function () {
        $manifest = json_decode(file_get_contents($this->manifestPath), true);
        $expectedSignature = $manifest['hooks']['copy_assets'];

        $commandFile = $this->pluginPath.'/src/Commands/CopyAssetsCommand.php';
        $content = file_get_contents($commandFile);

        expect($content)->toContain('$signature = \''.$expectedSignature.'\'');
    });

    it('copy_assets command has platform-specific methods', function () {
        $commandFile = $this->pluginPath.'/src/Commands/CopyAssetsCommand.php';
        $content = file_get_contents($commandFile);

        // Should check for platform
        expect($content)->toContain('$this->isAndroid()');
        expect($content)->toContain('$this->isIos()');
    });

    it('has valid assets configuration', function () {
        $manifest = json_decode(file_get_contents($this->manifestPath), true);

        // Assets are at top level with android/ios nested inside
        if (isset($manifest['assets'])) {
            expect($manifest['assets'])->toBeArray();

            if (isset($manifest['assets']['android'])) {
                expect($manifest['assets']['android'])->toBeArray();
            }

            if (isset($manifest['assets']['ios'])) {
                expect($manifest['assets']['ios'])->toBeArray();
            }
        }
    });
});
