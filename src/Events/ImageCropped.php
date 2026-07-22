<?php

namespace Vipertecpro\ImageCropper\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Illuminate\Queue\SerializesModels;

/**
 * Dispatched by the native crop screen when the user confirms a crop.
 *
 * The native side writes the cropped image to a new file and sends this event;
 * its public properties are populated by name from the native payload, so a
 * `#[On(ImageCropped::class)]` handler can type-hint `string $path` (and
 * optionally `?string $id`) and receive them directly.
 *
 * @property string $path Absolute path to the freshly written cropped JPEG.
 * @property ?string $id The correlation id passed to ImageCropper::open(), if any.
 */
class ImageCropped
{
    use Dispatchable, SerializesModels;

    public function __construct(
        public string $path,
        public ?string $id = null,
    ) {}
}
