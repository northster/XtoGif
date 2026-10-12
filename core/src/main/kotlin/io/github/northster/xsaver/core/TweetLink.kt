package io.github.northster.xsaver.core

/** Finds the post in shared text, e.g. "Look at this https://x.com/user/status/123?s=20". */
object TweetLink {
    private const val HOSTS = """(?:www\.|mobile\.|m\.)?(?:x|twitter|fxtwitter|vxtwitter|fixupx|fixvx|twittpr)\.com"""
    private val withUser = Regex("""$HOSTS/(?:#!/)?([A-Za-z0-9_]{1,15})/status(?:es)?/(\d{1,25})""", RegexOption.IGNORE_CASE)
    private val withoutUser = Regex("""$HOSTS/i/(?:web/)?status(?:es)?/(\d{1,25})""", RegexOption.IGNORE_CASE)
    private val shortLink = Regex("""https?://t\.co/[A-Za-z0-9]+""")

    fun parse(text: String): TweetRef? {
        withoutUser.find(text)?.let { return TweetRef(null, it.groupValues[1]) }
        val m = withUser.find(text) ?: return null
        val user = m.groupValues[1].takeUnless { it.equals("i", ignoreCase = true) }
        return TweetRef(user, m.groupValues[2])
    }

    /** A t.co link that needs a redirect to be followed before [parse] can read it. */
    fun findShortLink(text: String): String? = shortLink.find(text)?.value
}
