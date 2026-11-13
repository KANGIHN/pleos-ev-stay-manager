package kr.appdevchallenge.evstay.domain

import kotlin.math.abs

/** Result of reading the drain rate from recent SoC samples. */
sealed interface DrainMeasurement {
    /** Not enough consecutive drops yet; the prior model is used. */
    data class NotEnough(val dropEvents: Int) : DrainMeasurement

    /** The latest SoC change was upward (charging or a manual raise). */
    data object Rising : DrainMeasurement

    data class Measured(val percentPerHour: Double, val spanPercent: Double) : DrainMeasurement
}

private const val MS_PER_HOUR = 3_600_000.0

/**
 * SoC is reported in whole percent, so the rate is read between change points:
 * from the first drop to the last drop of the trailing run of drops.
 * If no further drop has happened for longer than the average interval, the extra wait is
 * counted too (the next percent has not dropped yet, so the rate is at most that slow).
 */
object DrainMeter {
    const val MIN_DROP_EVENTS = 3

    fun measure(samples: List<SocSample>, nowMs: Long): DrainMeasurement {
        if (samples.size < 2) return DrainMeasurement.NotEnough(0)
        val sorted = samples.sortedBy { it.timeMs }

        data class Change(val timeMs: Long, val soc: Double, val rising: Boolean)
        val changes = mutableListOf<Change>()
        for (i in 1 until sorted.size) {
            val diff = sorted[i].socPercent - sorted[i - 1].socPercent
            if (diff != 0.0) changes += Change(sorted[i].timeMs, sorted[i].socPercent, diff > 0)
        }
        if (changes.isEmpty()) return DrainMeasurement.NotEnough(0)
        if (changes.last().rising) return DrainMeasurement.Rising

        val drops = changes.takeLastWhile { !it.rising }
        if (drops.size < MIN_DROP_EVENTS) return DrainMeasurement.NotEnough(drops.size)

        val first = drops.first()
        val last = drops.last()
        val spanPercent = first.soc - last.soc
        val spanMs = (last.timeMs - first.timeMs).toDouble()
        if (spanMs <= 0.0 || spanPercent <= 0.0) return DrainMeasurement.NotEnough(drops.size)

        val avgIntervalMs = spanMs / (drops.size - 1)
        val stallMs = (nowMs - last.timeMs).toDouble()
        val effectiveMs = if (stallMs > avgIntervalMs) spanMs + (stallMs - avgIntervalMs) else spanMs
        return DrainMeasurement.Measured(spanPercent / (effectiveMs / MS_PER_HOUR), spanPercent)
    }
}

/**
 * Rolling SoC history on the app clock. A jump of [jumpThresholdPercent] or more between two
 * readings is treated as a discontinuity (e.g. a value typed into the Virtual Console, or a
 * charge session) and restarts the history instead of producing an absurd rate.
 */
class SocHistory(
    private val windowMs: Long = 12 * 3_600_000L,
    private val jumpThresholdPercent: Double = 5.0,
    private val maxSamples: Int = 500
) {
    private val samples = ArrayDeque<SocSample>()

    @Synchronized
    fun add(sample: SocSample) {
        val last = samples.lastOrNull()
        if (last != null) {
            if (sample.timeMs < last.timeMs) samples.clear()
            else if (abs(sample.socPercent - last.socPercent) >= jumpThresholdPercent) samples.clear()
            else if (sample.socPercent == last.socPercent && samples.size >= 2 &&
                samples[samples.size - 2].socPercent == last.socPercent
            ) {
                // Collapse runs of identical readings: keep only the first and the latest.
                samples.removeLast()
            }
        }
        samples.addLast(sample)
        while (samples.isNotEmpty() && sample.timeMs - samples.first().timeMs > windowMs) samples.removeFirst()
        while (samples.size > maxSamples) samples.removeFirst()
    }

    @Synchronized
    fun clear() = samples.clear()

    @Synchronized
    fun snapshot(): List<SocSample> = samples.toList()
}

/**
 * Decides which SoC readings belong to the current measurement segment.
 * A segment holds readings taken in P with unchanged climate settings; leaving P or changing
 * HVAC power/temperature/fan/AC/seat heat starts a new segment, so the measured rate reflects
 * the settings now in use. A value that briefly becomes unreadable (null) keeps its last
 * known value instead of starting a new segment.
 */
class SocRecorder(private val history: SocHistory = SocHistory()) {
    private var segmentKey: ClimateSettings? = null
    var segmentsStarted: Int = 0
        private set

    @Synchronized
    fun record(sample: SocSample?, parked: Boolean, climate: ClimateSettings) {
        if (!parked) {
            if (segmentKey != null || history.snapshot().isNotEmpty()) history.clear()
            segmentKey = null
            return
        }
        val previous = segmentKey
        val key = if (previous == null) climate else ClimateSettings(
            hvacOn = climate.hvacOn ?: previous.hvacOn,
            targetTempC = climate.targetTempC ?: previous.targetTempC,
            fanSpeed = climate.fanSpeed ?: previous.fanSpeed,
            acOn = climate.acOn ?: previous.acOn,
            seatHeatLevels = climate.seatHeatLevels.ifEmpty { previous.seatHeatLevels },
            seatHeatUnknownSeats = climate.seatHeatUnknownSeats
        )
        if (previous == null || key != previous) {
            history.clear()
            segmentsStarted++
        }
        segmentKey = key
        if (sample != null) history.add(sample)
    }

    @Synchronized
    fun reset() {
        history.clear()
        segmentKey = null
    }

    fun samples(): List<SocSample> = history.snapshot()
}
