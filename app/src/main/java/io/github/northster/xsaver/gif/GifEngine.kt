package io.github.northster.xsaver.gif

import android.media.MediaMetadataRetriever
import com.antonkarpenko.ffmpegkit.FFmpegKit
import com.antonkarpenko.ffmpegkit.FFmpegKitConfig
import com.antonkarpenko.ffmpegkit.FFprobeKit
import com.antonkarpenko.ffmpegkit.Level
import com.antonkarpenko.ffmpegkit.ReturnCode
import io.github.northster.xsaver.core.gif.FfmpegArgs
import io.github.northster.xsaver.core.gif.GifParams
import io.github.northster.xsaver.core.gif.SourceInfo
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** FFmpeg (FFmpegKit) with the two pass palette method: palettegen, then paletteuse. */
object GifEngine {
    init {
        FFmpegKitConfig.setLogLevel(Level.AV_LOG_ERROR)
    }

    /** Size, frame rate and length as shown (a rotated phone video reports its stored size, so swap it). */
    suspend fun probe(file: File): SourceInfo = withContext(Dispatchers.IO) {
        var width = 0
        var height = 0
        var durationSec = 0.0
        // MediaMetadataRetriever knows the rotation; FFprobe below fills in what it can't read (e.g. a GIF as input)
        val mmr = MediaMetadataRetriever()
        try {
            mmr.setDataSource(file.absolutePath)
            width = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_WIDTH)?.toIntOrNull() ?: 0
            height = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_HEIGHT)?.toIntOrNull() ?: 0
            val rotation = mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_VIDEO_ROTATION)?.toIntOrNull() ?: 0
            if (rotation == 90 || rotation == 270) width = height.also { height = width }
            durationSec = (mmr.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION)?.toLongOrNull() ?: 0) / 1000.0
        } catch (_: Exception) {
        } finally {
            runCatching { mmr.release() }
        }
        var fps = 0.0
        runCatching {
            val info = FFprobeKit.getMediaInformation(file.absolutePath).mediaInformation
            val video = info?.streams?.firstOrNull { it.type == "video" }
            fps = parseRate(video?.averageFrameRate) ?: parseRate(video?.realFrameRate) ?: 0.0
            if (width <= 0 || height <= 0) {
                width = video?.width?.toInt() ?: 0
                height = video?.height?.toInt() ?: 0
            }
            if (durationSec <= 0) durationSec = info?.duration?.toDoubleOrNull() ?: 0.0
        }
        if (width <= 0 || height <= 0 || durationSec <= 0) throw IOException("영상이 아니거나 읽을 수 없는 파일입니다")
        if (fps !in 1.0..240.0) fps = 30.0
        SourceInfo(width, height, fps, durationSec)
    }

    private fun parseRate(s: String?): Double? {
        if (s.isNullOrBlank()) return null
        val parts = s.split('/')
        val v = if (parts.size == 2) {
            val den = parts[1].toDoubleOrNull() ?: return null
            if (den == 0.0) return null
            (parts[0].toDoubleOrNull() ?: return null) / den
        } else s.toDoubleOrNull() ?: return null
        return v.takeIf { it.isFinite() && it > 0 }
    }

    /**
     * Rewrites an mp4's container without touching the picture (-c copy). X's own muxer ("Twitter-vork", iso5 brand,
     * 1/100 s time base) makes files that Samsung's gallery can neither play nor thumbnail; FFmpeg's muxer is the
     * plain isom layout every player reads.
     */
    suspend fun remux(input: File, out: File) {
        run(
            listOf(
                "-y", "-i", input.absolutePath, "-map", "0", "-c", "copy",
                "-video_track_timescale", "90000", "-movflags", "+faststart", "-f", "mp4", out.absolutePath,
            ),
        ) { }
        if (!out.exists() || out.length() == 0L) throw IOException("다시 포장한 영상이 비어 있습니다")
    }

    /**
     * One frame at [atSec] with the size, colours and dithering of [p], as a PNG: what the GIF will roughly look like.
     * Its palette comes from that frame alone, so it is a little kinder than the real one made for the whole clip.
     */
    suspend fun still(input: File, p: GifParams, atSec: Double, out: File) {
        run(
            listOf(
                "-y", "-ss", String.format(java.util.Locale.US, "%.3f", atSec), "-i", input.absolutePath, "-frames:v", "1",
                "-vf", "scale=${p.width}:-2:flags=lanczos,split[a][b];[a]palettegen=max_colors=${p.colors}:reserve_transparent=0[p];" +
                    "[b][p]paletteuse=dither=${p.dither.filter}",
                "-update", "1", "-f", "image2", out.absolutePath,
            ),
        ) { }
        if (!out.exists() || out.length() == 0L) throw IOException("미리보기 이미지가 비어 있습니다")
    }

    /** Makes [out] and returns its size. [onProgress] goes 0..1 over both passes. */
    suspend fun encode(input: File, p: GifParams, out: File, onProgress: (Float) -> Unit = {}): Long {
        val palette = File(out.parentFile, out.nameWithoutExtension + "_palette.png")
        try {
            run(FfmpegArgs.palette(input.absolutePath, p, palette.absolutePath)) { t -> onProgress(0.35f * fraction(t, p)) }
            run(FfmpegArgs.gif(input.absolutePath, palette.absolutePath, p, out.absolutePath)) { t ->
                onProgress(0.35f + 0.65f * fraction(t, p))
            }
        } finally {
            palette.delete()
        }
        if (!out.exists() || out.length() == 0L) throw IOException("변환 결과가 비어 있습니다")
        onProgress(1f)
        return out.length()
    }

    private fun fraction(timeMs: Double, p: GifParams) =
        (timeMs / 1000.0 / p.durationSec).toFloat().coerceIn(0f, 1f)

    private suspend fun run(args: List<String>, onTime: (Double) -> Unit) = suspendCancellableCoroutine { cont ->
        val session = FFmpegKit.executeWithArgumentsAsync(
            args.toTypedArray(),
            { s ->
                when {
                    ReturnCode.isSuccess(s.returnCode) -> if (cont.isActive) cont.resume(Unit)
                    ReturnCode.isCancel(s.returnCode) -> cont.cancel()
                    else -> if (cont.isActive) {
                        val log = s.allLogsAsString.orEmpty().trim().takeLast(400)
                        cont.resumeWithException(IOException("FFmpeg 오류 (${s.returnCode}): $log"))
                    }
                }
            },
            null,
            { stats -> onTime(stats.time) },
        )
        cont.invokeOnCancellation { FFmpegKit.cancel(session.sessionId) }
    }
}
