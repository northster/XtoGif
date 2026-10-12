package io.github.northster.xsaver.core

import kotlinx.serialization.json.JsonObject

/** api.vxtwitter.com (BetterTwitFix): the same idea as fxtwitter from another operator, so it covers fxtwitter outages. */
object VxTwitter {
    fun url(ref: TweetRef) = "https://api.vxtwitter.com/${ref.user ?: "i"}/status/${ref.id}"

    fun parse(httpCode: Int, body: String): FetchResult {
        val root = parseObject(body) ?: return FetchResult.Failed("HTTP $httpCode")
        root.str("error")?.let { return FetchResult.Failed(it) }
        if (httpCode !in 200..299 || root.str("tweetID") == null) return FetchResult.Failed("HTTP $httpCode")
        return FetchResult.Ok(parseTweet(root))
    }

    private fun parseTweet(t: JsonObject): Post {
        val media = t.arr("media_extended").mapNotNull { m ->
            val url = m.str("url") ?: return@mapNotNull null
            val w = m.obj("size")?.long("width")?.toInt() ?: 0
            val h = m.obj("size")?.long("height")?.toInt() ?: 0
            when (m.str("type")) {
                "image" -> MediaItem(MediaKind.PHOTO, Twimg.origPhoto(url), Twimg.smallPhoto(url), w, h)
                "video", "gif" -> {
                    val kind = if (m.str("type") == "gif" || Twimg.isGifUrl(url)) MediaKind.GIF else MediaKind.VIDEO
                    MediaItem(kind, url, m.str("thumbnail_url"), w, h, m.long("duration_millis") ?: 0)
                }
                else -> null
            }
        }
        return Post(
            id = t.str("tweetID").orEmpty(),
            author = t.str("user_screen_name").orEmpty(),
            text = t.str("text").orEmpty(),
            media = media,
            quote = t.obj("qrt")?.takeIf { it.str("tweetID") != null }?.let { parseTweet(it) },
        )
    }
}
