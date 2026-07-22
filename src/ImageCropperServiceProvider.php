<?php

namespace Vipertecpro\ImageCropper;

use Illuminate\Support\ServiceProvider;
use Vipertecpro\ImageCropper\Commands\CopyAssetsCommand;

class ImageCropperServiceProvider extends ServiceProvider
{
    public function register(): void
    {
        $this->app->singleton(ImageCropper::class, function () {
            return new ImageCropper;
        });
    }

    public function boot(): void
    {
        // Register plugin hook commands
        if ($this->app->runningInConsole()) {
            $this->commands([
                CopyAssetsCommand::class,
            ]);
        }
    }
}
