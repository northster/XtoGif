package io.github.northster.xsaver.core

import io.github.northster.xsaver.core.gif.AutoSizer
import io.github.northster.xsaver.core.gif.Dither
import io.github.northster.xsaver.core.gif.GifParams
import io.github.northster.xsaver.core.gif.GifSizeModel
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
        for (i in 1 until l.size) {
            assertTrue(GifSizeModel.quality(l[i]) <= GifSizeModel.quality(l[i - 1]))
            assertTrue(GifSizeModel.predict(src, l[i]) < GifSizeModel.predict(src, l[i - 1]))
        }
        // the first step down from the top is not a smaller picture
        assertTrue(l[0].width == 1080)
    }

    @Test fun fitsAndIsNearBest() = runTest {
        for (scale in listOf(0.3, 1.0, 3.0)) for (target in listOf(2_000_000L, 5_000_000L, 10_000_000L, 20_000_000L)) {
            val sizer = AutoSizer(src, target, Dither.BAYER, 0.0, 8.0, false)
            val out = sizer.run { fakeSize(it, scale) }
            val fits = assertIs<AutoSizer.Outcome.Fits>(out, "scale $scale target $target")
            assertTrue(fits.attempt.bytes <= target)
            assertTrue(fits.attempts.size <= 8)
            // nothing better on the ladder would have fit
            val idx = sizer.ladder.indexOf(fits.attempt.params)
            if (idx > 0) assertTrue(fakeSize(sizer.ladder[idx - 1], scale) > target, "scale $scale target $target idx $idx")
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
