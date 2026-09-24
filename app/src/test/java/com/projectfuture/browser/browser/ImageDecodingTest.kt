package com.projectfuture.browser.browser

import org.junit.Assert.assertEquals
import org.junit.Test

class ImageDecodingTest {

    @Test fun smallImageIsNotDownsampled() {
        assertEquals(1, computeInSampleSize(800, 600))
        assertEquals(1, computeInSampleSize(4096, 4096))
    }

    @Test fun hugeImageIsDownsampledToStayUnderTheBound() {
        // 8000x6000 -> half (4000x3000) already fits under the 4096 cap.
        assertEquals(2, computeInSampleSize(8000, 6000))
    }

    @Test fun massiveImageUsesALargerPowerOfTwo() {
        // The core bug: without any inSampleSize, a maliciously/accidentally
        // huge image (e.g. 30000x30000) would be decoded at full resolution -
        // a ~3.4GB ARGB_8888 allocation and an easy OutOfMemoryError crash from
        // a single <img> tag. Downsampling brings the allocated buffer down to
        // a bounded, sane size regardless of the source's declared dimensions.
        val sampleSize = computeInSampleSize(30000, 30000, maxDimension = 4096)
        assertEquals(8, sampleSize)
        assertEquals(true, 30000 / sampleSize <= 4096)
    }

    @Test fun extremeAspectRatioIsBoundedByTheLargerAxis() {
        // A 1px-wide, 50000px-tall image is still a huge allocation if only
        // the width is checked - the sample size must be driven by whichever
        // axis is larger, not by width alone.
        val sampleSize = computeInSampleSize(1, 50000, maxDimension = 4096)
        assertEquals(true, 50000 / sampleSize <= 4096)
    }

    @Test fun zeroOrNegativeDimensionsFromAFailedBoundsDecodeDoNotCrash() {
        // BitmapFactory.Options.outWidth/outHeight come back -1 for bytes it
        // can't even read bounds from (truncated/corrupt data); that must not
        // divide by zero or otherwise blow up before the real decode attempt
        // (which itself is expected to just return null and get skipped).
        assertEquals(1, computeInSampleSize(-1, -1))
        assertEquals(1, computeInSampleSize(0, 0))
    }
}
