package io.github.northster.xsaver.core

import kotlinx.serialization.json.JsonObject

/**
 * X's own endpoint behind embedded posts (publish.twitter.com, react-tweet): cdn.syndication.twimg.com/tweet-result.
 * No login and no third party in between, but undocumented, and posts marked sensitive come back as a "TweetTombstone".
 */
object Syndication {
    fun url(ref: TweetRef) = "https://cdn.syndication.twimg.com/tweet-result?id=${ref.id}&lang=en&token=${token(ref.id)}"

    /** Same as react-tweet: ((Number(id) / 1e15) * Math.PI).toString(36).replace(/(0+|\.)/g, '') */
    fun token(id: String): String {
        val n = id.toDoubleOrNull() ?: return "a"
        return JsRadix.toString((n / 1e15) * Math.PI, 36).replace(Regex("(0+|\\.)"), "")
    }

    fun parse(httpCode: Int, body: String): FetchResult {
        // protected accounts and deleted posts both give an empty 404
        if (httpCode == 404 || (httpCode in 200..299 && body.isBlank())) return FetchResult.Unavailable(UnavailableReason.HIDDEN)
        if (httpCode !in 200..299) return FetchResult.Failed("HTTP $httpCode")
        val root = parseObject(body) ?: return FetchResult.Failed("응답을 읽을 수 없음")
        if (root.str("__typename") == "TweetTombstone") {
            val text = root.obj("tombstone")?.obj("text")?.str("text").orEmpty()
            return FetchResult.Unavailable(UnavailableReason.LOGIN_REQUIRED, text)
        }
        if (root.str("id_str") == null) return FetchResult.Failed("게시물 정보 없음")
        return FetchResult.Ok(parsePost(root))
    }

    private fun parsePost(o: JsonObject): Post {
        val media = o.arr("mediaDetails").mapNotNull { m ->
            val thumb = m.str("media_url_https")
            val w = m.obj("original_info")?.long("width")?.toInt() ?: 0
            val h = m.obj("original_info")?.long("height")?.toInt() ?: 0
            when (m.str("type")) {
                "photo" -> thumb?.let { MediaItem(MediaKind.PHOTO, Twimg.origPhoto(it), Twimg.smallPhoto(it), w, h) }
                "video", "animated_gif" -> {
                    val info = m.obj("video_info")
                    val variants = info?.arr("variants").orEmpty().mapNotNull { v ->
                        v.str("url")?.let { Variant(it, v.long("bitrate") ?: 0, v.str("content_type").orEmpty()) }
                    }
                    val best = Twimg.bestMp4(variants) ?: return@mapNotNull null
                    val kind = if (m.str("type") == "animated_gif" || Twimg.isGifUrl(best.url)) MediaKind.GIF else MediaKind.VIDEO
                    MediaItem(kind, best.url, thumb, w, h, info?.long("duration_millis") ?: 0, best.bitrate)
                }
                else -> null
            }
        }
        return Post(
            id = o.str("id_str").orEmpty(),
            author = o.obj("user")?.str("screen_name").orEmpty(),
            text = o.str("text").orEmpty(),
            media = media,
            quote = o.obj("quoted_tweet")?.takeIf { it.str("id_str") != null }?.let { parsePost(it) },
        )
    }
}
