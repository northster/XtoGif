package io.github.northster.xsaver.core.gif

import java.util.Locale
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt

/** paletteuse dithering. The pattern of bayer compresses much better than error diffusion. */
enum class Dither(val label: String, val filter: String, val sizeFactor: Double) {
    BAYER("패턴 (용량↓)", "bayer:bayer_scale=3", 1.0),
    SIERRA("자연스럽게 (용량↑)", "sierra2_4a", 1.2),
    NONE("없음 (용량↓↓, 계단 현상)", "none", 0.85),
}

/** The source video as displayed (rotation applied). */
data class SourceInfo(val width: Int, val height: Int, val fps: Double, val durationSec: Double) {
    val maxFps: Int get() = fps.roundToInt().coerceIn(1, 60)
}

data class GifParams(
    val width: Int,
    val fps: Int,
    val colors: Int,
    val dither: Dither,
    val startSec: Double,
    val durationSec: Double,
) {
    fun height(src: SourceInfo) = (width.toDouble() * src.height / src.width).roundToInt().coerceAtLeast(1)

    fun describe(src: SourceInfo) =
        "${width}×${height(src)} · ${fps}fps · ${colors}색 · ${String.format(Locale.US, "%.1f", durationSec)}초"
}

/**
 * Rough GIF size: bytes ≈ k · pixels^0.92 · seconds · fps^0.85 · colorFactor · dither.
 * Sublinear in fps because with diff_mode=rectangle each frame only stores what changed, and fewer frames change more each.
 * The constant k depends on the content; it is recalibrated from every real encode.
 */
object GifSizeModel {
    const val DEFAULT_K = 1.2

    fun predict(src: SourceInfo, p: GifParams, k: Double = DEFAULT_K): Double {
        val pixels = p.width.toDouble() * p.height(src)
        val colorFactor = (log2(p.colors.toDouble()) / 8.0).pow(1.3)
        return k * pixels.pow(0.92) * p.durationSec * p.fps.toDouble().pow(0.85) * colorFactor * p.dither.sizeFactor
    }

    /** The k that would have predicted [actualBytes] for [p]. */
    fun calibrate(src: SourceInfo, p: GifParams, actualBytes: Long): Double =
        actualBytes / predict(src, p, 1.0).coerceAtLeast(1.0)
}

object FfmpegArgs {
    private fun num(d: Double) = String.format(Locale.US, "%.3f", d)

    private fun filters(p: GifParams) = "fps=${p.fps},scale=${p.width}:-2:flags=lanczos"

    /** Pass 1: one palette for the whole clip, weighted to what moves (stats_mode=diff). */
    fun palette(input: String, p: GifParams, palette: String) = listOf(
        "-y", "-ss", num(p.startSec), "-t", num(p.durationSec), "-i", input,
        "-vf", "${filters(p)},palettegen=max_colors=${p.colors}:stats_mode=diff:reserve_transparent=0",
        "-frames:v", "1", "-update", "1", palette,
    )

    /** Pass 2: map to the palette; diff_mode=rectangle only re-dithers the changed part of a frame, which keeps it small. */
    fun gif(input: String, palette: String, p: GifParams, output: String) = listOf(
        "-y", "-ss", num(p.startSec), "-t", num(p.durationSec), "-i", input, "-i", palette,
        "-lavfi", "${filters(p)}[v];[v][1:v]paletteuse=dither=${p.dither.filter}:diff_mode=rectangle",
        "-loop", "0", "-f", "gif", output,
    )
}
