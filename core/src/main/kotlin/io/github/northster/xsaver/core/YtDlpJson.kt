package io.github.northster.xsaver.core

import kotlinx.serialization.json.JsonObject

/** Reads `yt-dlp --dump-single-json`. yt-dlp's X extractor only returns videos and GIFs, never photos. */
object YtDlpJson {
    fun parse(json: String, ref: TweetRef): FetchResult {
        val root = parseObject(json) ?: return FetchResult.Failed("yt-dlp 출력을 읽을 수 없음")
        val entries = root.arr("entries").ifEmpty { listOf(root) }
        val media = entries.mapNotNull { e -> item(e) }
        if (media.isEmpty()) return FetchResult.Failed("yt-dlp: 영상 없음")
        val author = root.str("uploader_id") ?: entries.firstNotNullOfOrNull { it.str("uploader_id") } ?: ref.user.orEmpty()
        return FetchResult.Ok(Post(ref.id, author, root.str("description").orEmpty(), media))
    }

    private fun item(e: JsonObject): MediaItem? {
        val best = e.arr("formats")
            .filter { f -> f.str("protocol")?.startsWith("http") != false && f.str("url") != null && f.str("vcodec") != "none" }
            .filter { f -> f.str("ext") == "mp4" || f.str("url")!!.substringBefore('?').endsWith(".mp4") }
            .maxWithOrNull(compareBy<JsonObject> { it.double("tbr") ?: 0.0 }.thenBy { (it.long("width") ?: 0) * (it.long("height") ?: 0) })
        val url = best?.str("url") ?: e.str("url") ?: return null
        val kind = if (Twimg.isGifUrl(url)) MediaKind.GIF else MediaKind.VIDEO
        return MediaItem(
            kind = kind,
            url = url,
            thumbnailUrl = e.str("thumbnail"),
            width = (best?.long("width") ?: e.long("width") ?: 0).toInt(),
            height = (best?.long("height") ?: e.long("height") ?: 0).toInt(),
            durationMs = ((e.double("duration") ?: 0.0) * 1000).toLong(),
            bitrate = ((best?.double("tbr") ?: 0.0) * 1000).toLong(),
        )
    }
}
