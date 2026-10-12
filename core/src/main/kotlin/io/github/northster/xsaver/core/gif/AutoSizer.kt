package io.github.northster.xsaver.core.gif

import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Finds the best looking GIF that fits in [targetBytes].
 *
 * Idea from sticker-convert (github.com/laggykiller/sticker-convert, GPL-2.0): order the settings from best to
 * smallest and binary search that ladder with real encodes, keeping the best one under the limit. Two changes:
 * - The ladder is not one knob turned down evenly: [GifLadder] lowers the frame rate first (not below 15 fps), then
 *   the colours, then the resolution, and can keep one of them fixed.
 * - The next try is where a size model, recalibrated after every encode, says the limit is; plain halving is
 *   the fallback when the model misses twice in a row. Starting from the size constant measured on a sample and
 *   stopping at a fit within 10% of the target, it usually takes 1–3 encodes instead of ~6.
 * With [allowTrim] the ladder stops at 240 px; if that is still too big, the clip is cut short (kept from its start)
 * instead of going lower.
 */
class AutoSizer(
    private val src: SourceInfo,
    private val targetBytes: Long,
    private val dither: Dither,
    private val startSec: Double,
    private val durationSec: Double,
    private val allowTrim: Boolean,
    private val maxAttempts: Int = 8,
    private val maxWidth: Int = 1080,
    private val maxFps: Int = 30,
    /** Size constant already measured on this clip (the estimate's sample), so the first try is close. */
    private val initialK: Double = GifSizeModel.DEFAULT_K,
    /** A fit this close to the target ends the search: the next better step would gain little. */
    private val goodEnough: Double = 0.9,
    /** A setting fixed by hand; the search only moves the other two. */
    private val lock: Knob? = null,
    private val lockValue: Int = 0,
) {
    data class Attempt(val params: GifParams, val bytes: Long, val fits: Boolean)

    sealed interface Outcome {
        data class Fits(val attempt: Attempt, val attempts: List<Attempt>) : Outcome
        /** Even the smallest settings were too big; [smallest] is what they produced. */
        data class TooBig(val smallest: Attempt?, val attempts: List<Attempt>) : Outcome
    }

    val ladder: List<GifParams> by lazy {
        GifLadder(src, dither, startSec, durationSec, maxWidth, maxFps, allowTrim, lock, lockValue).steps
    }

    /** [encode] makes the GIF for the given settings and returns its size in bytes. */
    suspend fun run(onAttempt: (Attempt) -> Unit = {}, encode: suspend (GifParams) -> Long): Outcome {
        val attempts = mutableListOf<Attempt>()
        val l = ladder
        var k = initialK
        var lo = 0                 // indexes below lo are known (or assumed, sizes being monotone) to be too big
        var hi = l.lastIndex       // the best fitting index found so far is hi + 1
        var best: Attempt? = null
        var misses = 0
        val safety = 0.97

        suspend fun tryParams(p: GifParams): Attempt {
            val bytes = encode(p)
            k = GifSizeModel.calibrate(src, p, bytes)
            return Attempt(p, bytes, bytes <= targetBytes).also { attempts.add(it); onAttempt(it) }
        }

        while (lo <= hi && attempts.size < maxAttempts) {
            // the last try without anything fitting yet goes to the smallest settings, so the result is never empty-handed
            val lastChance = best == null && attempts.size == maxAttempts - 1
            val modelGuess = (lo..hi).firstOrNull { GifSizeModel.predict(src, l[it], k) <= targetBytes * safety } ?: hi
            val probe = when {
                lastChance -> hi
                misses >= 2 -> (lo + hi) / 2
                else -> modelGuess
            }
            val a = tryParams(l[probe])
            if (a.fits) {
                best = a
                hi = probe - 1
                misses = 0
                if (a.bytes >= targetBytes * goodEnough) break
            } else {
                lo = probe + 1
                misses++
            }
        }
        best?.let { return Outcome.Fits(it, attempts) }

        // nothing on the ladder fits: the smallest settings, possibly on a shorter clip
        val smallestTried = attempts.lastOrNull { it.params == l.last() } ?: tryParams(l.last())
        if (smallestTried.fits) return Outcome.Fits(smallestTried, attempts)
        if (!allowTrim) return Outcome.TooBig(smallestTried, attempts)
        var duration = durationSec
        var last = smallestTried
        repeat(3) {
            duration = max(0.3, duration * targetBytes * 0.92 / last.bytes)
            last = tryParams(l.last().copy(durationSec = duration))
            if (last.fits) return Outcome.Fits(last, attempts)
            if (duration <= 0.3) return Outcome.TooBig(last, attempts)
        }
        return Outcome.TooBig(last, attempts)
    }
}
