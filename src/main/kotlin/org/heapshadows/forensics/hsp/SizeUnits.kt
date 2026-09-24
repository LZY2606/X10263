package org.heapshadows.forensics.hsp

import java.math.BigInteger

/**
 * Unit normalization lives in the derived layer only. Raw text is never
 * rewritten; an unconvertible value yields null so callers keep the original
 * in place with a diagnostic instead of fabricating a zero/default.
 */
object SizeUnits {
    private val sizePattern = Regex("""^\s*(-?\d+)\s*([a-zA-Z]{0,3})?\s*$""")

    private val unitMultipliers = mapOf(
        "B" to BigInteger.ONE,
        "BYTE" to BigInteger.ONE,
        "BYTES" to BigInteger.ONE,
        "KB" to BigInteger.valueOf(1024L),
        "KIB" to BigInteger.valueOf(1024L),
        "MB" to BigInteger.valueOf(1024L * 1024),
        "MIB" to BigInteger.valueOf(1024L * 1024),
        "GB" to BigInteger.valueOf(1024L * 1024 * 1024),
    )

    /** Returns bytes normalized to the declared target unit, or null if the raw quantity cannot be converted. */
    fun parse(raw: String, declaredUnit: String): Long? {
        val match = sizePattern.matchEntire(raw) ?: return null
        val value = match.groupValues[1].toBigIntegerOrNull() ?: return null
        val rawUnit = (match.groupValues[2].ifEmpty { declaredUnit }).uppercase()
        val multiplier = unitMultipliers[rawUnit] ?: return null
        val declared = declaredUnit.uppercase()
        val target = unitMultipliers[declared] ?: return null
        if (multiplier >= target) {
            val factor = multiplier.divide(target)
            return runCatching { value.multiply(factor).longValueExact() }.getOrNull()
        }
        // Avoid silently inventing fractional bytes.
        return null
    }

    fun normalize(raw: String, declaredUnit: String, targetUnit: String = "B"): NormalizedResult? {
        val match = sizePattern.matchEntire(raw) ?: return null
        val value = match.groupValues[1].toBigIntegerOrNull() ?: return null
        if (value.signum() < 0) return null
        val rawUnit = (match.groupValues[2].ifEmpty { declaredUnit }).uppercase()
        val multiplier = unitMultipliers[rawUnit] ?: return null
        val target = unitMultipliers[targetUnit.uppercase()] ?: return null
        if (multiplier >= target) {
            val factor = multiplier.divide(target)
            val bytes = runCatching { value.multiply(factor).longValueExact() }.getOrNull() ?: return null
            return NormalizedResult(bytes, rawUnit)
        }
        return null
    }

    data class NormalizedResult(val bytes: Long, val sourceUnit: String)
}
