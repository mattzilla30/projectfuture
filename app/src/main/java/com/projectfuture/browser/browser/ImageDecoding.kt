package com.projectfuture.browser.browser

/**
 * Chooses a `BitmapFactory.Options.inSampleSize` (a power-of-two downsample
 * factor applied *during* decode, before the full-resolution pixel buffer is
 * ever allocated) for an image whose *reported* pixel dimensions are read
 * first via `inJustDecodeBounds`.
 *
 * Real bug this fixes: [Tab.collectAndDecodeImages] used to call
 * `BitmapFactory.decodeByteArray` directly with no [android.graphics.BitmapFactory.Options]
 * at all, so a `<img>` pointing at a huge (maliciously or accidentally so -
 * e.g. a mis-served RAW-sensor-sized JPEG, or a zip-bomb-style PNG with a
 * tiny byte count but an enormous declared width/height) image would have
 * Android allocate its *full* decoded ARGB_8888 pixel buffer - width *
 * height * 4 bytes, with no cap - before this engine ever got a chance to
 * lay it out or scale it down. A 30000x30000 image alone is a ~3.4GB
 * allocation, an easy `OutOfMemoryError` (and app crash) on a phone from a
 * single `<img>` tag. Android's own recommended fix is exactly this:
 * decode bounds only first, then decode again with a computed
 * `inSampleSize` so the buffer that actually gets allocated is already
 * close to the size this engine will display it at.
 *
 * [maxDimension] bounds how large (in either axis) the *sampled* decode is
 * allowed to come out - 4096px comfortably covers any real on-screen layout
 * size in this browser (viewport widths are phone-sized) while keeping the
 * worst-case allocation bounded to a few tens of MB regardless of how
 * large the source image claims to be.
 */
internal fun computeInSampleSize(width: Int, height: Int, maxDimension: Int = 4096): Int {
    if (width <= 0 || height <= 0) return 1
    val largest = maxOf(width, height)
    var sampleSize = 1
    while (largest / sampleSize > maxDimension) {
        sampleSize *= 2
    }
    return sampleSize
}
