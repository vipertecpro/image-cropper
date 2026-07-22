<?php

namespace Vipertecpro\ImageCropper\Facades;

use Illuminate\Support\Facades\Facade;

/**
 * @method static void open(string $path, array $options = [])
 *
 * @see \Vipertecpro\ImageCropper\ImageCropper
 */
class ImageCropper extends Facade
{
    protected static function getFacadeAccessor(): string
    {
        return \Vipertecpro\ImageCropper\ImageCropper::class;
    }
}
