package kr.appdevchallenge.evstay.domain

/** One State-of-Charge reading on the (possibly accelerated) app clock. */
data class SocSample(val timeMs: Long, val socPercent: Double)

/** Climate settings that drive the prior (model-based) drain estimate. Null = not readable. */
data class ClimateSettings(
    val hvacOn: Boolean? = null,
    val targetTempC: Double? = null,
    val fanSpeed: Int? = null,
    val acOn: Boolean? = null,
    /** Levels of the front seats whose heater could be read. */
    val seatHeatLevels: List<Int> = emptyList(),
    /** Front seats whose heater cannot be read at all (not just "off"), driver or passenger. */
    val seatHeatUnknownSeats: Int = 0
) {
    val seatHeatUnknown: Boolean get() = seatHeatUnknownSeats > 0
}

/** One front seat heater reading as the adapter sees it. */
data class SeatHeatReading(val level: Int?, val unreadable: Boolean)

object SeatHeat {
    /**
     * Splits the front seats into known levels and unknown seats. A seat without a value counts as
     * unknown, whichever side it is and whatever the reason: unsupported, still being read for the
     * first time, or supported but returning no value.
     */
    fun split(seats: List<SeatHeatReading>): Pair<List<Int>, Int> {
        val known = seats.filter { !it.unreadable && it.level != null }.map { it.level!! }
        return known to (seats.size - known.size)
    }
}

/** How much energy the trip home needs, by the best source available. */
sealed interface HomeEnergy {
    /** Navigation predicted SoC on arrival (requestTrip → TripInfo.estimatedSocAtDest). */
    /** [verified]: the navi answer was tied to this home (destination matched or distance cross-checked). */
    data class NaviSocAtDest(val socAtDestPercent: Double, val verified: Boolean = true) : HomeEnergy

    /** Distance home in km, with where it came from. [verified] is false only for an unconfirmed navi answer. */
    data class Distance(val km: Double, val source: DistanceSource, val verified: Boolean = true) : HomeEnergy

    data object Unknown : HomeEnergy
}

enum class DistanceSource { NAVI_TRIP, STRAIGHT_LINE, MANUAL }

/**
 * Model constants. Every value here is an assumption; the ones that change the result
 * (outside temperature, default capacity/efficiency, range width) are listed on screen.
 */
data class Assumptions(
    /** Entered by the user (default 0°C); no outside-temperature signal is read. */
    val outsideTempC: Double = 0.0,
    val baseLoadKw: Double = 0.2,
    val heatingKwPerDeg: Double = 0.08,
    val coolingKwPerDeg: Double = 0.10,
    val fanKwPerStep: Double = 0.03,
    val acKw: Double = 0.3,
    val seatHeatKwPerLevel: Double = 0.05,
    /** Used for the heavy bound when seat heat is unreadable: both front seats at this level. */
    val unknownSeatHeatLevel: Int = 3,
    val defaultTargetTempC: Double = 22.0,
    val defaultFanSpeed: Int = 3,
    val defaultCapacityKwh: Double = 77.4,
    val defaultEfficiencyKmPerKwh: Double = 5.0,
    val straightLineToRoadFactor: Double = 1.3,
    /**
     * Below this outside temperature the distance-based home-return energy is flagged as possibly
     * too low: no cold-weather driving correction is applied because no source for its size was found.
     */
    val coldDriveLimitC: Double = 10.0,
    val homeTripBufferFactor: Double = 1.1,
    /** Home distances above this are treated as bad data, not as a long trip. */
    val maxPlausibleHomeKm: Double = 1500.0,
    /** Width of the shown range. Set by hand, not validated against real vehicles. */
    val priorRangeFraction: Double = 0.25,
    val measuredRangeFraction: Double = 0.10,
    val warnLeadHours: Double = 0.5,
    val warnSocGapPercent: Double = 3.0,
    val fullTrustSpanPercent: Double = 4.0,
    val historyWindowHours: Double = 12.0
)

data class StayInput(
    val nowMs: Long,
    val socPercent: Double?,
    val socSamples: List<SocSample>,
    val batteryCapacityKwh: Double?,
    val efficiencyKmPerKwh: Double?,
    val climate: ClimateSettings,
    /**
     * Lowest HVAC setpoint the user accepts for suggested alternatives (not the actual cabin temperature,
     * which is not read); alternatives with a lower setpoint, or with HVAC off, are not suggested.
     */
    val minCabinTempC: Double? = null,
    /** Stay condition: the user keeps the HVAC on (no HVAC-off alternatives). Default off keeps old behaviour. */
    val keepHvacOn: Boolean = false,
    /** Stay condition: a passenger is in the car (the passenger seat heater is never turned off in alternatives). */
    val passengerPresent: Boolean = false,
    /** Home-energy sources in priority order; clearly bogus ones are rejected and the next is used. */
    val homeCandidates: List<HomeEnergy>,
    val userMarginPercent: Double,
    /** Planned end of this stay on the app clock (full date-time, epoch ms); null = not set. */
    val targetEndMs: Long? = null,
    /** Home inputs the adapters already discarded (e.g. invalid coordinates), shown as rejections. */
    val homeInputIssues: List<RejectedHome> = emptyList(),
    val assumptions: Assumptions = Assumptions()
)

enum class StayStatus { NO_DATA, CHARGING, HOME_UNREACHABLE, BELOW_RESERVE, WARNING, OK }

enum class DrainSource { PRIOR, BLENDED, MEASURED }

/** Whether the home-return part of the reserve line could be computed. */
enum class HomeStatus {
    /** SoC needed to get home is known and reachable with the current SoC. */
    COMPUTED,

    /** No usable home information: the reserve is the user margin only (귀가 보장 계산 불가). */
    UNKNOWN,

    /** A plausible route needs more SoC than is left (or more than 100%). */
    UNREACHABLE
}

data class HoursRange(val low: Double, val high: Double) {
    val mid: Double get() = (low + high) / 2.0
}

data class ReserveLine(
    /** SoC needed to reach home, clamped to 0..100 for display; null when unknown. */
    val homeNeedPercent: Double?,
    /** Unclamped need, so "needs 114%" can be explained. */
    val homeNeedRawPercent: Double?,
    val marginPercent: Double,
    val totalPercent: Double,
    val homeSource: HomeEnergy,
    val homeStatus: HomeStatus,
    val rejected: List<RejectedHome> = emptyList(),
    /** Home need comes from a distance and the entered outside temperature is cold: no cold correction applied. */
    val coldUncorrected: Boolean = false,
    /** False when the home need comes from a navi answer that could not be tied to this home. */
    val homeVerified: Boolean = true
)

/** A home input rejected as clearly bogus ("신뢰할 수 없음"), with the reason. */
data class RejectedHome(val label: String, val reason: String)

/**
 * [deltaMinutes] compares the middle of the range; [lowDeltaMinutes] compares the short (conservative)
 * end, which is what the planned-time check uses.
 */
data class SettingComparison(
    val label: String,
    val deltaMinutes: Int,
    /** Short-end gain rounded to 5 minutes, for display in the comparison list only. */
    val lowDeltaMinutes: Int = deltaMinutes,
    /** Short-end gain before rounding; decisions ("covers the shortfall") use this. */
    val lowDeltaExactMinutes: Double = lowDeltaMinutes.toDouble(),
    /** The alternative keeps the HVAC (heating/cooling) on. */
    val keepsHvacOn: Boolean = true
)

/**
 * Planned stay vs. the conservative (low) end of the range. [met] only means "expected under
 * the current assumptions". [reached] = the planned end time has passed (stay complete).
 */
data class TargetCheck(
    val targetHours: Double,
    val met: Boolean,
    /** Rounded for display; never 0 or positive when [met] is false. */
    val marginMinutes: Int,
    val reached: Boolean = false,
    /** Unrounded margin (negative = short); decisions use this. */
    val marginExactMinutes: Double = marginMinutes.toDouble(),
    /**
     * When short: SoC (%) that, added now, lets the short end of the range reach the planned time
     * with the current setting, and the same in kWh. Null when met or reached.
     */
    val chargePercent: Double? = null,
    val chargeKwh: Double? = null,
    /** Even a full battery would not cover the planned time with the current setting. */
    val chargeBeyondFull: Boolean = false,
    /**
     * The home-return part rests on an unconfirmed navi answer: "충족 예상" must not be shown;
     * the verdict is held until a trusted input (e.g. a directly entered distance) is available.
     */
    val homeVerdictHeld: Boolean = false
)

data class StayEstimate(
    val status: StayStatus,
    val socPercent: Double?,
    val reserve: ReserveLine,
    val drainPercentPerHour: Double?,
    val drainSource: DrainSource?,
    val measuredPercentPerHour: Double?,
    val priorPercentPerHour: Double?,
    val stayHours: HoursRange?,
    val comparisons: List<SettingComparison>,
    val assumptions: List<String>,
    val warning: String?,
    /** True when some climate inputs were unreadable and the range covers both possibilities. */
    val partial: Boolean = false,
    val rangeFraction: Double? = null,
    val target: TargetCheck? = null
)
