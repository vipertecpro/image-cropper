<?php

namespace Vipertecpro\ImageCropper\Events;

use Illuminate\Foundation\Events\Dispatchable;
use Illuminate\Queue\SerializesModels;

/**
 * Dispatched by the native crop screen when the user cancels (no file produced).
 *
 * @property ?string $id The correlation id passed to ImageCropper::open(), if any.
 */
class CropCancelled
{
    use Dispatchable, SerializesModels;

    public function __construct(
        public ?string $id = null,
    ) {}
}
