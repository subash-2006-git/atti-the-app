package com.subash.atti

import com.subash.atti.data.ImageCompressor
import org.junit.Assert.assertEquals
import org.junit.Test

class ImageCompressorTest {
    @Test fun `big landscape photo is scaled so its long side is 1600`() {
        assertEquals(1600 to 1200, ImageCompressor.targetSize(4000, 3000))
    }

    @Test fun `big portrait photo keeps its shape`() {
        assertEquals(900 to 1600, ImageCompressor.targetSize(2160, 3840))
    }

    @Test fun `small photos are never enlarged`() {
        assertEquals(640 to 480, ImageCompressor.targetSize(640, 480))
    }

    @Test fun `very thin images keep at least one pixel`() {
        assertEquals(1600 to 1, ImageCompressor.targetSize(20000, 2))
    }
}
