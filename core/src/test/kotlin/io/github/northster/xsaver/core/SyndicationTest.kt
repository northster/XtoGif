package io.github.northster.xsaver.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class SyndicationTest {
    @Test fun tokenMatchesJavaScript() {
        // values from node: ((Number(id)/1e15)*Math.PI).toString(36).replace(/(0+|\.)/g,'')
        assertEquals("6dq1a2xwd93", Syndication.token("20"))
        assertEquals("4d2v7cbm2xj", Syndication.token("1800000000000000000"))
        assertEquals("4qrfmg1ie6h", Syndication.token("1956789012345678901"))
        assertEquals("2zqic77uqyk", Syndication.token("1234567890123456789"))
        assertEquals("3tol417ti8o", Syndication.token("1577730467436138524"))
    }

    @Test fun radix() {
        assertEquals("0.000000006dq1a2xwd93", JsRadix.toString(20 / 1e15 * Math.PI, 36))
        assertEquals("ff", JsRadix.toString(255.0, 16))
        assertEquals("0.1", JsRadix.toString(0.5, 2))
        assertEquals("-a.i", JsRadix.toString(-10.5, 36))
    }

    @Test fun parse() {
        val json = """
        {"__typename":"Tweet","id_str":"100","text":"hi","user":{"screen_name":"alice"},
         "mediaDetails":[
          {"type":"photo","media_url_https":"https://pbs.twimg.com/media/P1.jpg","original_info":{"width":4096,"height":2048}},
          {"type":"video","media_url_https":"https://pbs.twimg.com/ext_tw_video_thumb/1/pu/img/t.jpg",
           "video_info":{"duration_millis":12345,"variants":[
             {"content_type":"application/x-mpegURL","url":"https://video.twimg.com/ext_tw_video/1/pu/pl/a.m3u8"},
             {"bitrate":256000,"content_type":"video/mp4","url":"https://video.twimg.com/ext_tw_video/1/pu/vid/avc1/320x180/s.mp4"},
             {"bitrate":2176000,"content_type":"video/mp4","url":"https://video.twimg.com/ext_tw_video/1/pu/vid/avc1/1280x720/l.mp4"}]}},
          {"type":"animated_gif","media_url_https":"https://pbs.twimg.com/tweet_video_thumb/G.jpg",
           "video_info":{"variants":[{"bitrate":0,"content_type":"video/mp4","url":"https://video.twimg.com/tweet_video/G.mp4"}]}}],
         "quoted_tweet":{"id_str":"99","text":"q","user":{"screen_name":"bob"},
           "mediaDetails":[{"type":"photo","media_url_https":"https://pbs.twimg.com/media/Q.png"}]}}
        """
        val r = assertIs<FetchResult.Ok>(Syndication.parse(200, json))
        val p = r.post
        assertEquals("alice", p.author)
        assertEquals(listOf(MediaKind.PHOTO, MediaKind.VIDEO, MediaKind.GIF), p.media.map { it.kind })
        assertEquals("https://pbs.twimg.com/media/P1?format=jpg&name=orig", p.media[0].url)
        assertEquals("https://video.twimg.com/ext_tw_video/1/pu/vid/avc1/1280x720/l.mp4", p.media[1].url)
        assertEquals(12345, p.media[1].durationMs)
        assertEquals("mp4", p.media[2].extension)
        assertEquals("bob", p.quote!!.author)
        assertEquals("image/png", p.quote!!.media[0].mimeType)
    }

    @Test fun unavailable() {
        assertIs<FetchResult.Unavailable>(Syndication.parse(404, ""))
        val t = assertIs<FetchResult.Unavailable>(Syndication.parse(200, """{"__typename":"TweetTombstone","tombstone":{"text":{"text":"Age-restricted adult content."}}}"""))
        assertEquals(UnavailableReason.LOGIN_REQUIRED, t.reason)
        assertIs<FetchResult.Failed>(Syndication.parse(500, "oops"))
    }
}
