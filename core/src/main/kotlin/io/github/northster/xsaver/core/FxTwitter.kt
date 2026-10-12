package io.github.northster.xsaver.core

import kotlinx.serialization.json.JsonObject

/** api.fxtwitter.com (FixTweet): community run, the most complete answer (sensitive posts, quotes, all variants). */
object FxTwitter {
    fun url(ref: TweetRef) = "https://api.fxtwitter.com/${ref.user ?: "i"}/status/${ref.id}"

    fun parse(httpCode: Int, body: String): FetchResult {
        val root = parseObject(body)
        when (root?.long("code")?.toInt() ?: httpCode) {
            401 -> return FetchResult.Unavailable(UnavailableReason.PRIVATE, root?.str("message").orEmpty())
            404 -> return FetchResult.Unavailable(UnavailableReason.NOT_FOUND, root?.str("message").orEmpty())
        }
        if (root == null) return FetchResult.Failed("HTTP $httpCode")
        val tweet = root.obj("tweet") ?: return FetchResult.Failed(root.str("message") ?: "HTTP $httpCode")
        return FetchResult.Ok(parseTweet(tweet))
    }

    private fun parseTweet(t: JsonObject): Post {
        val mediaObj = t.obj("media")
        val all = mediaObj?.arr("all")?.takeIf { it.isNotEmpty() }
            ?: (mediaObj?.arr("photos").orEmpty() + mediaObj?.arr("videos").orEmpty())
        val media = all.mapNotNull { m ->
            val url = m.str("url") ?: return@mapNotNull null
            val w = m.long("width")?.toInt() ?: 0
            val h = m.long("height")?.toInt() ?: 0
            when (m.str("type")) {
                "photo" -> MediaItem(MediaKind.PHOTO, Twimg.origPhoto(url), Twimg.smallPhoto(url), w, h)
                "video", "gif" -> {
                    val variants = (m.arr("variants") + m.arr("formats")).mapNotNull { v ->
                        v.str("url")?.let { Variant(it, v.long("bitrate") ?: 0, v.str("content_type") ?: v.str("container")?.let { c -> "video/$c" }.orEmpty()) }
                    }
                    val best = Twimg.bestMp4(variants + Variant(url, 0, m.str("format").orEmpty())) ?: Variant(url, 0, "")
                    val kind = if (m.str("type") == "gif" || Twimg.isGifUrl(best.url)) MediaKind.GIF else MediaKind.VIDEO
                    val duration = ((m.double("duration") ?: 0.0) * 1000).toLong()
                    MediaItem(kind, best.url, m.str("thumbnail_url"), w, h, duration, best.bitrate)
                }
                else -> null
            }
        }
        return Post(
            id = t.str("id").orEmpty(),
            author = t.obj("author")?.str("screen_name").orEmpty(),
            text = t.str("text").orEmpty(),
            media = media,
            quote = t.obj("quote")?.let { parseTweet(it) },
        )
    }
}
