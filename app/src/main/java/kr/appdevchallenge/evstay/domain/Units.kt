package kr.appdevchallenge.evstay.domain

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * The SDK getters return bare Floats without documented units.
 * - Battery capacity: **confirmed Wh** on Pleos Connect Emulator v2.1.0 (Virtual Console 77.4 kWh
 *   was reported as 77400.0). Other vehicles are not checked.
 * - Remaining range and efficiency: **unit not confirmed**. They are read with plausibility rules
 *   (AAOS properties use meters and Wh) and shown with their raw value as "단위 미확인".
 * Values outside a physical range are rejected instead of guessed further.
 */
object Units {
    /** Battery capacity in Wh (confirmed on the emulator) → kWh. Outside 5–250 kWh is unknown. */
    fun capacityKwh(raw: Float?): Double? {
        val v = raw?.toDouble() ?: return null
        if (!v.isFinite() || v <= 0.0) return null
        return (v / 1000.0).takeIf { it in 5.0..250.0 }
    }

    /** Remaining range → km (unit not confirmed). Values above 2000 are read as meters. 0 or less means unknown. */
    fun rangeKm(raw: Float?): Double? {
        val v = raw?.toDouble() ?: return null
        if (!v.isFinite() || v <= 0.0) return null
        return if (v > 2000.0) v / 1000.0 else v
    }

    /** Efficiency → km/kWh (unit not confirmed). 1–15 is km/kWh; 50–400 is read as Wh/km. Otherwise unknown. */
    fun efficiencyKmPerKwh(raw: Float?): Double? {
        val v = raw?.toDouble() ?: return null
        if (!v.isFinite()) return null
        return when (v) {
            in 1.0..15.0 -> v
            in 50.0..400.0 -> 1000.0 / v
            else -> null
        }
    }

    /** km/kWh implied by remaining range at the current SoC, when both are known. */
    fun efficiencyFromRange(rangeKm: Double?, socPercent: Double?, capacityKwh: Double?): Double? {
        if (rangeKm == null || socPercent == null || capacityKwh == null) return null
        if (rangeKm <= 0.0 || socPercent <= 0.0) return null
        return efficiencyKmPerKwh((rangeKm / (capacityKwh * socPercent / 100.0)).toFloat())
    }

    /** Great-circle distance in km. */
    fun haversineKm(lat1: Double, lon1: Double, lat2: Double, lon2: Double): Double {
        val r = 6371.0
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val h = sin(dLat / 2).pow(2) +
            cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLon / 2).pow(2)
        return 2 * r * asin(sqrt(h))
    }
}
