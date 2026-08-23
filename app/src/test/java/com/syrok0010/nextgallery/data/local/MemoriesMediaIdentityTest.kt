package com.syrok0010.nextgallery.data.local

import org.junit.Assert.assertEquals
import org.junit.Test

class MemoriesMediaIdentityTest {
    @Test
    fun `AUID and size based BUID match Memories Android golden values`() {
        val aliases = MemoriesMediaIdentity.calculate(
            baseName = "IMG_0042.jpg",
            sizeBytes = 4_321_987L,
            dateTakenMillis = 1_717_100_123_456L,
            imageUniqueId = null,
        )

        assertEquals("b37756808471733d7d2220145f79cae8", aliases.auid)
        assertEquals("cf09d03f2fdcadb19918438e341e10db", aliases.buid)
    }

    @Test
    fun `BUID prefers EXIF image unique ID and AUID does not use timeline fallback date`() {
        val aliases = MemoriesMediaIdentity.calculate(
            baseName = "IMG_0042.jpg",
            sizeBytes = 4_321_987L,
            dateTakenMillis = 0,
            imageUniqueId = "camera-unique-42",
        )

        assertEquals("e107f808ffffbe5719385756bec92b16", aliases.auid)
        assertEquals("040d2b385c96e6411d94be87a3ba64bf", aliases.buid)
    }
}
