package io.github.northster.xsaver.core

import java.util.Locale

object FileNames {
    /** authorId_postId_index, e.g. "nasa_1789012345678901234_2" (index starts at 1). */
    fun base(author: String, postId: String, index: Int) = "${safe(author)}_${safe(postId)}_$index"

    fun safe(s: String) = s.replace(Regex("[^A-Za-z0-9_-]"), "_").ifEmpty { "unknown" }

    /** File names of other apps' videos, without the extension, made safe. */
    fun fromDisplayName(name: String?) =
        name?.substringBeforeLast('.')?.replace(Regex("""[\\/:*?"<>|\s]+"""), "_")?.take(80)?.ifEmpty { null } ?: "video"
}

object Bytes {
    /** Decimal units (1 MB = 1,000,000 bytes), so a target like "10 MB" also stays under limits counted in MiB. */
    const val MB = 1_000_000L

    fun format(bytes: Long): String = when {
        bytes < 0 -> "?"
        bytes < 1000 -> "$bytes B"
        bytes < MB -> String.format(Locale.US, "%.0f KB", bytes / 1000.0)
        bytes < 100 * MB -> String.format(Locale.US, "%.2f MB", bytes / MB.toDouble())
        else -> String.format(Locale.US, "%.0f MB", bytes / MB.toDouble())
    }
}
