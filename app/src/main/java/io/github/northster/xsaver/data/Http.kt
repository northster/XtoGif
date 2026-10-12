package io.github.northster.xsaver.data

import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import java.util.concurrent.TimeUnit

object Http {
    // a normal browser UA: some of the endpoints answer bots differently
    const val USER_AGENT =
        "Mozilla/5.0 (Linux; Android 16; SM-F976N) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Mobile Safari/537.36"

    val client: OkHttpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .followRedirects(true)
            .addInterceptor { chain ->
                chain.proceed(chain.request().newBuilder().header("User-Agent", USER_AGENT).build())
            }
            .build()
    }

    fun get(url: String): Request = Request.Builder().url(url).get().build()

    /** Size from a HEAD request, or null if the server doesn't say. */
    fun contentLength(url: String): Long? =
        client.newCall(Request.Builder().url(url).head().build()).execute().use { r: Response ->
            if (!r.isSuccessful) null else r.header("Content-Length")?.toLongOrNull()
        }
}
