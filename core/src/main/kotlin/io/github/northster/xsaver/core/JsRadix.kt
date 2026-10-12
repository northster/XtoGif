package io.github.northster.xsaver.core

import kotlin.math.floor
import kotlin.math.max

/**
 * JavaScript's Number.prototype.toString(radix) for doubles, ported from V8's DoubleToRadixCString.
 * The syndication endpoint wants a token computed with it (see [Syndication.token]).
 */
object JsRadix {
    private const val DIGITS = "0123456789abcdefghijklmnopqrstuvwxyz"

    fun toString(input: Double, radix: Int): String {
        require(radix in 2..36)
        if (input.isNaN()) return "NaN"
        if (input.isInfinite()) return if (input > 0) "Infinity" else "-Infinity"
        val negative = input < 0
        val value = if (negative) -input else input

        var integer = floor(value)
        var fraction = value - integer
        // only as many fraction digits as the double's precision allows
        var delta = max(Math.nextUp(0.0), 0.5 * (Math.nextUp(value) - value))
        val fractionDigits = StringBuilder()
        if (fraction >= delta) {
            do {
                fraction *= radix
                delta *= radix
                val digit = fraction.toInt()
                fractionDigits.append(DIGITS[digit])
                fraction -= digit
                // round half to even
                if (fraction > 0.5 || (fraction == 0.5 && (digit and 1) == 1)) {
                    if (fraction + delta > 1) {
                        // round up, carrying into earlier digits
                        while (true) {
                            if (fractionDigits.isEmpty()) {
                                integer += 1
                                break
                            }
                            val last = DIGITS.indexOf(fractionDigits.last())
                            fractionDigits.setLength(fractionDigits.length - 1)
                            if (last + 1 < radix) {
                                fractionDigits.append(DIGITS[last + 1])
                                break
                            }
                        }
                        break
                    }
                }
            } while (fraction >= delta)
        }

        val integerDigits = StringBuilder()
        // digits below the double's precision are zeros
        while (Math.getExponent(integer / radix) - 52 > 0) {
            integer /= radix
            integerDigits.append('0')
        }
        do {
            val remainder = integer % radix
            integerDigits.append(DIGITS[remainder.toInt()])
            integer = (integer - remainder) / radix
        } while (integer > 0)

        val sb = StringBuilder()
        if (negative) sb.append('-')
        sb.append(integerDigits.reverse())
        if (fractionDigits.isNotEmpty()) sb.append('.').append(fractionDigits)
        return sb.toString()
    }
}
