package io.github.northster.xsaver.core

import kotlin.test.Test
import kotlin.test.assertEquals

class TwimgTest {
    @Test fun photos() {
        assertEquals("https://pbs.twimg.com/media/GAbc-12_x?format=jpg&name=orig", Twimg.origPhoto("https://pbs.twimg.com/media/GAbc-12_x.jpg"))
        assertEquals("https://pbs.twimg.com/media/GAbc?format=png&name=orig", Twimg.origPhoto("https://pbs.twimg.com/media/GAbc?format=png&name=small"))
        assertEquals("https://pbs.twimg.com/media/GAbc?format=jpg&name=orig", Twimg.origPhoto("https://pbs.twimg.com/media/GAbc.jpg:large"))
        assertEquals("png", Twimg.photoExtension("https://pbs.twimg.com/media/GAbc?format=png&name=orig"))
        assertEquals("jpg", Twimg.photoExtension("https://pbs.twimg.com/media/GAbc?format=jpg&name=orig"))
    }

    @Test fun bestVideo() {
        val v = listOf(
            Variant("https://video.twimg.com/ext_tw_video/1/pu/pl/x.m3u8?tag=12", 0, "application/x-mpegURL"),
            Variant("https://video.twimg.com/ext_tw_video/1/pu/vid/avc1/480x270/a.mp4?tag=12", 632000, "video/mp4"),
            Variant("https://video.twimg.com/ext_tw_video/1/pu/vid/avc1/1280x720/c.mp4?tag=12", 2176000, "video/mp4"),
            Variant("https://video.twimg.com/ext_tw_video/1/pu/vid/avc1/640x360/b.mp4?tag=12", 950000, "video/mp4"),
        )
        assertEquals(2176000, Twimg.bestMp4(v)!!.bitrate)
        // no bitrates: resolution decides
        assertEquals("c", Twimg.bestMp4(v.map { it.copy(bitrate = 0) })!!.url.substringAfterLast('/').substringBefore('.'))
    }
}
