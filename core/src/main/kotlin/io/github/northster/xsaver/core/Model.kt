package io.github.northster.xsaver.core

/** A post's address: [user] is null for links without one (x.com/i/status/...). */
data class TweetRef(val user: String?, val id: String) {
    val url: String get() = "https://x.com/${user ?: "i"}/status/$id"
}

enum class MediaKind { PHOTO, VIDEO, GIF }

/**
 * One photo, video or GIF of a post. [url] is what gets downloaded: the original photo (name=orig) or the mp4 with the
 * highest bitrate. X serves GIFs as mp4 too, so a [MediaKind.GIF] is an mp4 file.
 */
data class MediaItem(
    val kind: MediaKind,
    val url: String,
    val thumbnailUrl: String? = null,
    val width: Int = 0,
    val height: Int = 0,
    val durationMs: Long = 0,
    val bitrate: Long = 0,
) {
    val extension: String get() = if (kind == MediaKind.PHOTO) Twimg.photoExtension(url) else "mp4"
    val mimeType: String
        get() = when (extension) {
            "png" -> "image/png"
            "webp" -> "image/webp"
            "gif" -> "image/gif"
            "mp4" -> "video/mp4"
            else -> "image/jpeg"
        }
}

data class Post(
    val id: String,
    val author: String,
    val text: String,
    val media: List<MediaItem>,
    val quote: Post? = null,
)

enum class UnavailableReason(val message: String) {
    PRIVATE("비공개 계정의 게시물"),
    LOGIN_REQUIRED("로그인이 필요한 게시물 (연령 제한 등)"),
    NOT_FOUND("삭제됐거나 없는 게시물"),
    HIDDEN("비공개이거나 삭제된 게시물"),
}

sealed interface FetchResult {
    data class Ok(val post: Post) : FetchResult
    /** The post exists but can't be read without logging in, or is gone. */
    data class Unavailable(val reason: UnavailableReason, val detail: String = "") : FetchResult
    /** Network trouble, an API change, a rate limit... the next source may still work. */
    data class Failed(val message: String) : FetchResult
}
