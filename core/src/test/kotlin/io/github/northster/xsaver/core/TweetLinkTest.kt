package io.github.northster.xsaver.core

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class TweetLinkTest {
    @Test fun shareText() {
        assertEquals(TweetRef("NASA", "1789012345678901234"), TweetLink.parse("보세요 https://x.com/NASA/status/1789012345678901234?s=46&t=abc"))
        assertEquals(TweetRef("jack", "20"), TweetLink.parse("https://twitter.com/jack/status/20"))
        assertEquals(TweetRef("a_b", "123"), TweetLink.parse("https://mobile.twitter.com/a_b/statuses/123/photo/1"))
        assertEquals(TweetRef("u", "5"), TweetLink.parse("fxtwitter.com/u/status/5"))
        assertEquals(TweetRef(null, "77"), TweetLink.parse("https://x.com/i/status/77"))
        assertEquals(TweetRef(null, "78"), TweetLink.parse("https://twitter.com/i/web/status/78"))
        assertNull(TweetLink.parse("https://x.com/NASA"))
        assertNull(TweetLink.parse("https://example.com/a/status/1"))
        assertEquals("https://t.co/AbC123", TweetLink.findShortLink("see https://t.co/AbC123 now"))
    }
}
