package io.github.northster.xsaver.core

data class Variant(val url: String, val bitrate: Long, val contentType: String)

/** URL rules of X's media servers (pbs.twimg.com for photos, video.twimg.com for videos and GIFs). */
object Twimg {
    private val photoRe = Regex("""^https?://pbs\.twimg\.com/media/([A-Za-z0-9_-]+)(?:\.([A-Za-z0-9]+))?(?:[?:](.*))?$""")
    private val resolutionRe = Regex("""/(\d{2,5})x(\d{2,5})/""")

    /** The original upload: pbs.twimg.com/media/ID?format=jpg&name=orig. Other URLs are returned unchanged. */
    fun origPhoto(url: String): String = photo(url, "orig")

    fun smallPhoto(url: String): String = photo(url, "small")

    private fun photo(url: String, name: String): String {
        val m = photoRe.find(url) ?: return url
        val ext = m.groupValues[2].ifEmpty { queryParam(m.groupValues[3], "format") ?: "jpg" }
        return "https://pbs.twimg.com/media/${m.groupValues[1]}?format=$ext&name=$name"
    }

    fun photoExtension(url: String): String {
        val m = photoRe.find(url)
        val ext = when {
            m == null -> url.substringBefore('?').substringAfterLast('.', "jpg")
            m.groupValues[2].isNotEmpty() -> m.groupValues[2]
            else -> queryParam(m.groupValues[3], "format") ?: "jpg"
        }.lowercase()
        return when (ext) {
            "jpeg" -> "jpg"
            "jpg", "png", "webp", "gif" -> ext
            else -> "jpg"
        }
    }

    private fun queryParam(query: String, key: String): String? =
        query.split('&').firstOrNull { it.startsWith("$key=") }?.substringAfter('=')?.takeIf { it.isNotEmpty() }

    /** X GIFs live under /tweet_video/ and are always mp4. */
    fun isGifUrl(url: String) = "/tweet_video/" in url

    /** The mp4 with the highest bitrate; the resolution in the URL breaks ties and stands in for a missing bitrate. */
    fun bestMp4(variants: List<Variant>): Variant? =
        variants.filter { it.contentType.isEmpty() || it.contentType == "video/mp4" || it.url.substringBefore('?').endsWith(".mp4") }
            .filterNot { it.url.substringBefore('?').endsWith(".m3u8") }
            .maxWithOrNull(compareBy<Variant> { it.bitrate }.thenBy { pixels(it.url) })

    fun pixels(url: String): Long {
        val m = resolutionRe.find(url) ?: return 0
        return m.groupValues[1].toLong() * m.groupValues[2].toLong()
    }
}
