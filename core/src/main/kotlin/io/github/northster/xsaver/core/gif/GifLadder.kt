package io.github.northster.xsaver.core.gif

import kotlin.math.min
import kotlin.math.roundToInt

/** The three settings the size is traded against. */
enum class Knob { FPS, COLORS, WIDTH }

/**
 * The settings, best first, that the automatic mode steps down through. Order of sacrifice: frame rate first (down to
 * [MIN_FPS], never below), then colours (down to [MIN_AUTO_COLORS]), then resolution. A knob fixed by hand ([lock])
 * keeps its value and the other two follow the same order. Every step is smaller than the one before, so the list
 * can be binary searched.
 */
class GifLadder(
    private val src: SourceInfo,
    private val dither: Dither,
    private val startSec: Double,
    private val durationSec: Double,
    maxWidth: Int = 1080,
    maxFps: Int = 30,
    /** Cutting the clip is allowed: resolution stops at 240 px instead of going lower. */
    allowTrim: Boolean = false,
    private val lock: Knob? = null,
    private val lockValue: Int = 0,
) {
    val widths: List<Int>
    val fpsValues: List<Int>
    /** All colour counts a slider offers; the automatic steps stop at [MIN_AUTO_COLORS]. */
    val colorValues: List<Int> = COLORS

    init {
        val wMax = min(src.width, maxWidth).coerceAtLeast(2)
        val wMin = min(if (allowTrim) 240 else 160, wMax)
        val w = generateSequence(wMax.toDouble()) { it * 0.9 }
            .map { (it / 2).roundToInt() * 2 }
            .takeWhile { it >= wMin }
            .toMutableList()
        if (w.isEmpty() || w.last() != wMin) w.add(wMin)
        widths = w.distinct()
        val fpsTop = min(src.maxFps, maxFps)
        val fpsMin = min(MIN_FPS, fpsTop)
        fpsValues = (listOf(fpsTop) + listOf(30, 25, 24, 20, 15, 12, 10)).filter { it in fpsMin..fpsTop }.distinct()
    }

    private fun stepsOf(k: Knob): List<Int> = when (k) {
        Knob.FPS -> fpsValues
        Knob.COLORS -> COLORS.filter { it >= MIN_AUTO_COLORS }
        Knob.WIDTH -> widths
    }

    val steps: List<GifParams> by lazy {
        val free = Knob.entries.filter { it != lock } // already in sacrifice order
        val value = mutableMapOf<Knob, Int>()
        Knob.entries.forEach { value[it] = if (it == lock) lockValue else stepsOf(it).first() }
        val out = mutableListOf(make(value))
        for (k in free) {
            for (v in stepsOf(k).drop(1)) {
                value[k] = v
                out.add(make(value))
            }
        }
        out
    }

    private fun make(v: Map<Knob, Int>) =
        GifParams(v.getValue(Knob.WIDTH), v.getValue(Knob.FPS), v.getValue(Knob.COLORS), dither, startSec, durationSec)

    companion object {
        const val MIN_FPS = 10
        const val MIN_AUTO_COLORS = 64
        val COLORS = listOf(256, 192, 128, 96, 64, 48, 32)
    }
}
