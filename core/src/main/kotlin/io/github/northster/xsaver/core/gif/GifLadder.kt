package io.github.northster.xsaver.core.gif

import kotlin.math.min
import kotlin.math.roundToInt

/** The three settings the size is traded against. */
enum class Knob { FPS, COLORS, WIDTH }

/**
 * The settings, best first, that the automatic mode steps down through. Order of sacrifice: frame rate first, then
 * colours, then resolution. Settings in [locks] keep their value and only the others move.
 *
 * Floors: with two or three settings free, the frame rate stops at [AUTO_MIN_FPS] and the colours at
 * [AUTO_MIN_COLORS]; a setting left alone to fit the size may go down to the slider's minimum ([MIN_FPS], 32 colours).
 * Every step is smaller than the one before, so the list can be binary searched.
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
    private val locks: Map<Knob, Int> = emptyMap(),
) {
    /** Values the sliders offer, best first. */
    val widths: List<Int>
    val fpsValues: List<Int>
    val colorValues: List<Int> = COLORS

    private val free = Knob.entries.filter { it !in locks } // already in sacrifice order

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
        fpsValues = (listOf(fpsTop) + FPS).filter { it in min(MIN_FPS, fpsTop)..fpsTop }.distinct()
    }

    private fun stepsOf(k: Knob): List<Int> {
        val alone = free == listOf(k)
        return when (k) {
            Knob.FPS -> fpsValues.filter { alone || it >= AUTO_MIN_FPS }.ifEmpty { fpsValues.take(1) }
            Knob.COLORS -> COLORS.filter { alone || it >= AUTO_MIN_COLORS }
            Knob.WIDTH -> widths
        }
    }

    val steps: List<GifParams> by lazy {
        val value = mutableMapOf<Knob, Int>()
        Knob.entries.forEach { value[it] = locks[it] ?: stepsOf(it).first() }
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
        /** Lowest frame rate on the slider, and for the frame rate when it is the only setting left to fit the size. */
        const val MIN_FPS = 6
        /** Lowest frame rate the automatic mode picks while other settings can still give. */
        const val AUTO_MIN_FPS = 12
        const val AUTO_MIN_COLORS = 64
        val FPS = listOf(30, 25, 24, 20, 15, 12, 10, 8, 6)
        val COLORS = listOf(256, 192, 128, 96, 64, 48, 32)
    }
}
