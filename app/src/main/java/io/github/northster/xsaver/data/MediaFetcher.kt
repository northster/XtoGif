package io.github.northster.xsaver.data

import android.content.Context
import com.yausername.youtubedl_android.YoutubeDL
import com.yausername.youtubedl_android.YoutubeDLRequest
import io.github.northster.xsaver.core.FetchResult
import io.github.northster.xsaver.core.FxTwitter
import io.github.northster.xsaver.core.Syndication
import io.github.northster.xsaver.core.TweetLink
import io.github.northster.xsaver.core.TweetRef
import io.github.northster.xsaver.core.UnavailableReason
import io.github.northster.xsaver.core.VxTwitter
import io.github.northster.xsaver.core.YtDlpJson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.Request

/**
 * Tries the sources in order until one returns the post:
 * 1. X's embed endpoint: first party, fast, nobody else sees what is downloaded; refuses sensitive posts
 * 2. fxtwitter: handles sensitive posts, quotes and all video variants
 * 3. vxtwitter: same kind of service, different operator, for when fxtwitter is down
 * 4. yt-dlp: heavy (Python), videos only, but updates itself when X changes something
 * A private account (fxtwitter says so explicitly) ends the search: no other source can read it either.
 */
class MediaFetcher(private val context: Context, private val settings: Settings) {
    data class Outcome(val result: FetchResult, val source: Source?, val log: List<String>)

    suspend fun fetch(ref: TweetRef, onLog: (String) -> Unit): Outcome = withContext(Dispatchers.IO) {
        val log = mutableListOf<String>()
        fun note(s: String) { log.add(s); onLog(s) }
        var unavailable: FetchResult.Unavailable? = null
        for (source in Source.entries) {
            if (!settings.isEnabled(source)) continue
            ensureActive()
            note("${source.label}: 시도 중…")
            val result = try {
                when (source) {
                    Source.SYNDICATION -> http(Syndication.url(ref), Syndication::parse)
                    Source.FXTWITTER -> http(FxTwitter.url(ref), FxTwitter::parse)
                    Source.VXTWITTER -> http(VxTwitter.url(ref), VxTwitter::parse)
                    Source.YTDLP -> YtDlp.fetch(context, ref)
                }
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                FetchResult.Failed(e.message ?: e.javaClass.simpleName)
            }
            when (result) {
                is FetchResult.Ok -> {
                    note("${source.label}: 성공 (미디어 ${result.post.media.size}개)")
                    return@withContext Outcome(result, source, log)
                }
                is FetchResult.Unavailable -> {
                    note("${source.label}: ${result.reason.message}")
                    // keep the most specific reason (fxtwitter knows "private" from "deleted")
                    if (unavailable == null || unavailable.reason == UnavailableReason.HIDDEN) unavailable = result
                    if (result.reason == UnavailableReason.PRIVATE) break
                }
                is FetchResult.Failed -> note("${source.label}: 실패 - ${result.message}")
            }
        }
        Outcome(unavailable ?: FetchResult.Failed("모든 방법이 실패했습니다"), null, log)
    }

    private fun http(url: String, parse: (Int, String) -> FetchResult): FetchResult =
        Http.client.newCall(Http.get(url)).execute().use { r ->
            parse(r.code, r.body?.string().orEmpty())
        }

    companion object {
        /** Follows a t.co link to the post it points at. */
        suspend fun resolveShortLink(url: String): TweetRef? = withContext(Dispatchers.IO) {
            runCatching {
                Http.client.newCall(Request.Builder().url(url).head().build()).execute().use { r ->
                    TweetLink.parse(r.request.url.toString())
                }
            }.getOrNull()
        }
    }
}

/** yt-dlp through youtubedl-android. Python is unpacked on first use, which takes a few seconds. */
object YtDlp {
    private val initLock = Mutex()
    @Volatile private var ready = false

    private suspend fun init(context: Context) = initLock.withLock {
        if (!ready) {
            YoutubeDL.getInstance().init(context.applicationContext)
            ready = true
        }
    }

    suspend fun fetch(context: Context, ref: TweetRef): FetchResult = withContext(Dispatchers.IO) {
        init(context)
        val request = YoutubeDLRequest(ref.url).apply {
            addOption("--dump-single-json")
            addOption("--no-warnings")
        }
        val response = YoutubeDL.getInstance().execute(request)
        YtDlpJson.parse(response.out, ref)
    }

    suspend fun update(context: Context): String = withContext(Dispatchers.IO) {
        init(context)
        val status = YoutubeDL.getInstance().updateYoutubeDL(context.applicationContext)
        "$status · ${YoutubeDL.getInstance().version(context.applicationContext) ?: "?"}"
    }

    suspend fun version(context: Context): String? = withContext(Dispatchers.IO) {
        runCatching { init(context); YoutubeDL.getInstance().version(context.applicationContext) }.getOrNull()
    }
}
