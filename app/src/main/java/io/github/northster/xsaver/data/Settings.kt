package io.github.northster.xsaver.data

import android.content.Context
import io.github.northster.xsaver.core.gif.Dither

enum class Source(val label: String, val note: String) {
    SYNDICATION("X 임베드 (cdn.syndication.twimg.com)", "X 공식 서버, 중간 업체 없음. 민감한 게시물은 거부"),
    FXTWITTER("fxtwitter API", "가장 자세함 (민감한 게시물, 인용, 모든 화질)"),
    VXTWITTER("vxtwitter API", "fxtwitter와 다른 운영자, 장애 대비"),
    YTDLP("yt-dlp", "영상/GIF만. 느리지만 스스로 업데이트 가능"),
}

class Settings(context: Context) {
    private val prefs = context.getSharedPreferences("settings", Context.MODE_PRIVATE)

    var includeQuote: Boolean
        get() = prefs.getBoolean("include_quote", false)
        set(v) = prefs.edit().putBoolean("include_quote", v).apply()

    /** Posts with an X GIF (an mp4) open with the "GIF" switch on, so saving goes through the converter. */
    var gifAsGif: Boolean
        get() = prefs.getBoolean("gif_as_gif", true)
        set(v) = prefs.edit().putBoolean("gif_as_gif", v).apply()

    fun isEnabled(s: Source) = prefs.getBoolean("source_${s.name}", true)
    fun setEnabled(s: Source, on: Boolean) = prefs.edit().putBoolean("source_${s.name}", on).apply()

    var targetMb: String
        get() = prefs.getString("target_mb", "8") ?: "8"
        set(v) = prefs.edit().putString("target_mb", v).apply()

    var dither: Dither
        get() = runCatching { Dither.valueOf(prefs.getString("dither", null) ?: "") }.getOrDefault(Dither.BAYER)
        set(v) = prefs.edit().putString("dither", v.name).apply()

    var allowTrim: Boolean
        get() = prefs.getBoolean("allow_trim", false)
        set(v) = prefs.edit().putBoolean("allow_trim", v).apply()

    /** Top of the converter's size slider (MB); the 100% GIF is often far bigger than anything worth sending. */
    var sliderMaxMb: Float
        get() = prefs.getFloat("slider_max_mb", 20f)
        set(v) = prefs.edit().putFloat("slider_max_mb", v).apply()

    /** Where the size slider starts, in % of its maximum. */
    var sliderDefaultPercent: Int
        get() = prefs.getInt("slider_default_percent", 50)
        set(v) = prefs.edit().putInt("slider_default_percent", v).apply()

    var maxGifWidth: Int
        get() = prefs.getInt("max_gif_width", 1080)
        set(v) = prefs.edit().putInt("max_gif_width", v).apply()
}
