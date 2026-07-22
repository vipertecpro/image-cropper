/**
 * ImageCropper Plugin for NativePHP Mobile — JavaScript bridge (legacy web-view apps).
 *
 * NOTE: the primary consumer in v4 is a SuperNative `NativeComponent` calling the
 * PHP facade (`ImageCropper::open(...)`) and handling the `ImageCropped` /
 * `CropCancelled` events with `#[On]`. This JS wrapper is provided for
 * Livewire/Inertia web-view screens. Because the crop result is delivered
 * asynchronously via a native event (not the call's return value), subscribe to
 * the events with the `#nativephp` `On()` helper.
 *
 * @example
 *   import { imageCropper } from '@vipertecpro/image-cropper';
 *   import { On } from '#nativephp';
 *
 *   On('native:Vipertecpro\\ImageCropper\\Events\\ImageCropped', ({ path }) => {
 *       // use the cropped file path
 *   });
 *
 *   await imageCropper.open('/path/to/photo.jpg', { aspectRatio: 1, outputSize: 1024 });
 */

const baseUrl = '/_native/api/call';

/**
 * Internal bridge call function.
 * @private
 */
async function bridgeCall(method, params = {}) {
    const response = await fetch(baseUrl, {
        method: 'POST',
        headers: {
            'Content-Type': 'application/json',
            'X-CSRF-TOKEN': document.querySelector('meta[name="csrf-token"]')?.content || ''
        },
        body: JSON.stringify({ method, params })
    });

    const result = await response.json();

    if (result.status === 'error') {
        throw new Error(result.message || 'Native call failed');
    }

    return result.data;
}

/**
 * Open the native crop screen for an image.
 *
 * The result is NOT returned here — listen for the `ImageCropped` /
 * `CropCancelled` native events instead (see the module example above).
 *
 * @param {string} path - Absolute path to the source image.
 * @param {Object} [options]
 * @param {number} [options.aspectRatio=1] - Crop-frame width/height ratio.
 * @param {number} [options.outputSize=1024] - Longest edge of the crop, in px.
 * @param {string} [options.id] - Optional correlation id echoed back on the event.
 * @returns {Promise<void>}
 */
export async function open(path, options = {}) {
    return bridgeCall('ImageCropper.Open', {
        path,
        aspectRatio: options.aspectRatio ?? 1,
        outputSize: options.outputSize ?? 1024,
        id: options.id ?? null
    });
}

/**
 * ImageCropper namespace object.
 */
export const imageCropper = { open };

export default imageCropper;
