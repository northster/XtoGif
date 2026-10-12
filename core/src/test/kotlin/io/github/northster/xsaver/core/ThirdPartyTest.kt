package io.github.northster.xsaver.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class ThirdPartyTest {
    @Test fun fxtwitter() {
        val json = """
        {"code":200,"message":"OK","tweet":{"id":"100","text":"t","author":{"screen_name":"alice"},
          "media":{"all":[
            {"type":"photo","url":"https://pbs.twimg.com/media/P1.jpg?name=orig","width":10,"height":20},
            {"type":"gif","url":"https://video.twimg.com/tweet_video/G.mp4","thumbnail_url":"https://pbs.twimg.com/tweet_video_thumb/G.jpg","width":320,"height":240,"duration":0,"format":"video/mp4"},
            {"type":"video","url":"https://video.twimg.com/amplify_video/1/vid/avc1/720x1280/m.mp4","duration":30.5,"width":720,"height":1280,"format":"video/mp4",
             "variants":[{"content_type":"video/mp4","bitrate":10368000,"url":"https://video.twimg.com/amplify_video/1/vid/avc1/1080x1920/h.mp4"},
                         {"content_type":"video/mp4","bitrate":2176000,"url":"https://video.twimg.com/amplify_video/1/vid/avc1/720x1280/m.mp4"}]}]},
          "quote":{"id":"99","text":"q","author":{"screen_name":"bob"},"media":{"photos":[{"type":"photo","url":"https://pbs.twimg.com/media/Q.jpg"}]}}}}
        """
        val p = assertIs<FetchResult.Ok>(FxTwitter.parse(200, json)).post
        assertEquals(listOf(MediaKind.PHOTO, MediaKind.GIF, MediaKind.VIDEO), p.media.map { it.kind })
        assertEquals("https://pbs.twimg.com/media/P1?format=jpg&name=orig", p.media[0].url)
        assertEquals("https://video.twimg.com/amplify_video/1/vid/avc1/1080x1920/h.mp4", p.media[2].url)
        assertEquals(30500, p.media[2].durationMs)
        assertEquals("bob", p.quote!!.author)
        assertEquals(1, p.quote!!.media.size)

        assertEquals(UnavailableReason.PRIVATE, assertIs<FetchResult.Unavailable>(FxTwitter.parse(401, """{"code":401,"message":"PRIVATE_TWEET"}""")).reason)
        assertEquals(UnavailableReason.NOT_FOUND, assertIs<FetchResult.Unavailable>(FxTwitter.parse(404, """{"code":404,"message":"NOT_FOUND"}""")).reason)
        assertIs<FetchResult.Failed>(FxTwitter.parse(500, """{"code":500,"message":"API_FAIL"}"""))
        assertIs<FetchResult.Failed>(FxTwitter.parse(502, "<html>bad gateway</html>"))
    }

    @Test fun vxtwitter() {
        val json = """
        {"tweetID":"100","user_screen_name":"alice","text":"t","mediaURLs":[],
         "media_extended":[{"type":"image","url":"https://pbs.twimg.com/media/P1.jpg","size":{"width":1,"height":2}},
                           {"type":"gif","url":"https://video.twimg.com/tweet_video/G.mp4","thumbnail_url":"x"},
                           {"type":"video","url":"https://video.twimg.com/ext_tw_video/1/pu/vid/avc1/1280x720/l.mp4","duration_millis":5000}],
         "qrt":{"tweetID":"99","user_screen_name":"bob","media_extended":[]}}
        """
        val p = assertIs<FetchResult.Ok>(VxTwitter.parse(200, json)).post
        assertEquals(listOf(MediaKind.PHOTO, MediaKind.GIF, MediaKind.VIDEO), p.media.map { it.kind })
        assertEquals("bob", p.quote!!.author)
        assertIs<FetchResult.Failed>(VxTwitter.parse(500, """{"error":"Failed to scan your link!"}"""))
    }

    @Test fun ytdlp() {
        val json = """
        {"_type":"playlist","uploader_id":"alice","entries":[
          {"id":"1","thumbnail":"th","duration":3.2,"formats":[
            {"format_id":"hls-1","protocol":"m3u8_native","url":"https://video.twimg.com/a.m3u8","ext":"mp4","tbr":3000},
            {"format_id":"http-256","protocol":"https","url":"https://video.twimg.com/ext_tw_video/1/pu/vid/avc1/320x180/s.mp4","ext":"mp4","tbr":256,"width":320,"height":180},
            {"format_id":"http-2176","protocol":"https","url":"https://video.twimg.com/ext_tw_video/1/pu/vid/avc1/1280x720/l.mp4","ext":"mp4","tbr":2176,"width":1280,"height":720}]},
          {"id":"2","url":"https://video.twimg.com/tweet_video/G.mp4","formats":[]}]}
        """
        val p = assertIs<FetchResult.Ok>(YtDlpJson.parse(json, TweetRef(null, "100"))).post
        assertEquals("alice", p.author)
        assertEquals("https://video.twimg.com/ext_tw_video/1/pu/vid/avc1/1280x720/l.mp4", p.media[0].url)
        assertEquals(listOf(MediaKind.VIDEO, MediaKind.GIF), p.media.map { it.kind })
    }

    @Test fun names() {
        assertEquals("alice_100_1", FileNames.base("alice", "100", 1))
        assertEquals("2.50 MB", Bytes.format(2_500_000))
    }
}
