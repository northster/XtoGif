package io.github.northster.xsaver.core

import io.github.northster.xsaver.core.gif.AutoSizer
import io.github.northster.xsaver.core.gif.Dither
import io.github.northster.xsaver.core.gif.GifParams
import io.github.northster.xsaver.core.gif.GifLadder
import io.github.northster.xsaver.core.gif.GifSizeModel
import io.github.northster.xsaver.core.gif.Knob
import io.github.northster.xsaver.core.gif.SourceInfo
import kotlinx.coroutines.test.runTest
import kotlin.math.pow
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

class AutoSizerTest {
    private val src = SourceInfo(1280, 720, 30.0, 8.0)

    /** A "real" encoder that behaves differently from the model (other exponents and scale). */
    private fun fakeSize(p: GifParams, scale: Double): Long {
        val pixels = p.width.toDouble() * p.height(src)
        val colors = (kotlin.math.log2(p.colors.toDouble()) / 8).pow(1.6)
        return (scale * pixels.pow(0.97) * p.durationSec * p.fps.toDouble().pow(0.75) * colors).toLong()
    }

    @Test fun ladderIsMonotone() {
        val l = AutoSizer(src, 5_000_000, Dither.BAYER, 0.0, 8.0, false).ladder
        assertTrue(l.size > 20)
        for (i in 1 until l.size) assertTrue(GifSizeModel.predict(src, l[i]) < GifSizeModel.predict(src, l[i - 1]))
        // frames go first (to 12), then colours (to 64), then resolution; only then 6 fps and 32 colours
        val usual = l.takeWhile { it.fps >= 12 && it.colors >= 64 }
        assertTrue(usual.last().width == 160 && usual.last().fps == 12 && usual.last().colors == 64)
        assertTrue(l.last().fps == 6 && l.last().colors == 32)
        val firstColorDrop = l.indexOfFirst { it.colors < 256 }
        val firstWidthDrop = l.indexOfFirst { it.width < 1080 }
        assertTrue(l.take(firstColorDrop).last().fps == 12)
        assertTrue(firstColorDrop < firstWidthDrop)
        assertTrue(l[firstWidthDrop].colors == 64)
    }

    @Test fun lockedKnobsStay() {
        // resolution locked: frames to 12, colours to 64, then past the floors to 6 fps and 32 colours
        val l = GifLadder(src, Dither.BAYER, 0.0, 8.0, locks = mapOf(Knob.WIDTH to 640)).steps
        assertTrue(l.all { it.width == 640 })
        assertTrue(l.any { it.fps == 12 && it.colors == 64 })
        assertTrue(l.last().fps == 6 && l.last().colors == 32)
        val c = GifLadder(src, Dither.BAYER, 0.0, 8.0, locks = mapOf(Knob.COLORS to 32)).steps
        assertTrue(c.all { it.colors == 32 })
        assertTrue(c.first().width == 1080 && c.last().width == 160)
        // the frame rate alone may go down to 6 fps
        val f = GifLadder(src, Dither.BAYER, 0.0, 8.0, locks = mapOf(Knob.WIDTH to 640, Knob.COLORS to 128)).steps
        assertTrue(f.all { it.width == 640 && it.colors == 128 })
        assertTrue(f.last().fps == 6)
    }

    @Test fun fitsAndIsNearBest() = runTest {
        for (scale in listOf(0.3, 1.0, 3.0)) for (target in listOf(2_000_000L, 5_000_000L, 10_000_000L, 20_000_000L)) {
            val sizer = AutoSizer(src, target, Dither.BAYER, 0.0, 8.0, false)
            val out = sizer.run { fakeSize(it, scale) }
            val fits = assertIs<AutoSizer.Outcome.Fits>(out, "scale $scale target $target")
            assertTrue(fits.attempt.bytes <= target)
            assertTrue(fits.attempts.size <= 8)
            // nothing better on the ladder would have fit, or it is within 10% of the target
            val idx = sizer.ladder.indexOf(fits.attempt.params)
            if (idx > 0 && fits.attempt.bytes < target * 0.9) {
                assertTrue(fakeSize(sizer.ladder[idx - 1], scale) > target, "scale $scale target $target idx $idx")
            }
            println("scale $scale target $target -> ${fits.attempt.params.describe(src)} ${fits.attempt.bytes} in ${fits.attempts.size} tries")
        }
    }

    @Test fun trims() = runTest {
        val sizer = AutoSizer(src, 300_000, Dither.BAYER, 0.0, 8.0, allowTrim = true)
        val out = assertIs<AutoSizer.Outcome.Fits>(sizer.run { fakeSize(it, 3.0) })
        assertTrue(out.attempt.params.durationSec < 8.0)
        assertTrue(out.attempt.params.width >= 240)
        println("trim -> ${out.attempt.params.describe(src)}")
        val noTrim = AutoSizer(src, 1_000, Dither.BAYER, 0.0, 8.0, allowTrim = false).run { fakeSize(it, 3.0) }
        assertIs<AutoSizer.Outcome.TooBig>(noTrim)
    }
}
