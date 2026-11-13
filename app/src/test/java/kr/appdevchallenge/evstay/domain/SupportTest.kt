package kr.appdevchallenge.evstay.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class SupportTest {

    @Test
    fun socHistory_resetsOnJump() {
        val h = SocHistory()
        h.add(SocSample(0, 35.0))
        h.add(SocSample(1000, 34.0))
        h.add(SocSample(2000, 22.0)) // typed into the console
        assertEquals(listOf(SocSample(2000, 22.0)), h.snapshot())
    }

    @Test
    fun socHistory_collapsesFlatRuns() {
        val h = SocHistory()
        h.add(SocSample(0, 35.0))
        h.add(SocSample(1000, 35.0))
        h.add(SocSample(2000, 35.0))
        h.add(SocSample(3000, 34.0))
        assertEquals(listOf(0L, 2000L, 3000L), h.snapshot().map { it.timeMs })
    }

    @Test
    fun socHistory_dropsOldSamples() {
        val h = SocHistory(windowMs = 10_000)
        h.add(SocSample(0, 35.0))
        h.add(SocSample(5_000, 34.0))
        h.add(SocSample(20_000, 33.0))
        assertEquals(listOf(20_000L), h.snapshot().map { it.timeMs })
    }

    @Test
    fun drainMeter_needsThreeDrops() {
        val s = listOf(SocSample(0, 35.0), SocSample(1000, 34.0), SocSample(2000, 33.0))
        assertTrue(DrainMeter.measure(s, 2000) is DrainMeasurement.NotEnough)
        assertTrue(DrainMeter.measure(emptyList(), 0) is DrainMeasurement.NotEnough)
    }

    @Test
    fun units_normalize() {
        // Confirmed on the emulator: Virtual Console 77.4 kWh is reported as 77400.0 (Wh).
        assertEquals(77.4, Units.capacityKwh(77_400f)!!, 1e-3)
        // A kWh-looking value is not silently accepted: as Wh it is 0.08 kWh, i.e. unknown.
        assertNull(Units.capacityKwh(77.4f))
        assertNull(Units.capacityKwh(0f))
        assertEquals(350.0, Units.rangeKm(350_000f)!!, 1e-6)
        assertEquals(350.0, Units.rangeKm(350f)!!, 1e-6)
        assertNull(Units.rangeKm(0f))
        assertNull(Units.rangeKm(-1f))
        assertEquals(5.0, Units.efficiencyKmPerKwh(200f)!!, 1e-6)
        assertNull(Units.efficiencyKmPerKwh(0f))
        assertEquals(5.0, Units.efficiencyFromRange(135.45, 35.0, 77.4)!!, 1e-3)
    }

    @Test
    fun haversine_seoulToSuwon() {
        val km = Units.haversineKm(37.5665, 126.9780, 37.2636, 127.0286)
        assertEquals(34.0, km, 1.5)
    }

    @Test
    fun clock_acceleratesOnlyInDemo() {
        var real = 1_000L
        val c = StayClock({ real }, demoSpeed = 60)
        real += 1_000
        assertEquals(2_000L, c.nowMs())
        c.setDemo(true)
        real += 1_000
        assertEquals(62_000L, c.nowMs())
        c.setDemo(false)
        real += 1_000
        assertEquals(63_000L, c.nowMs())
    }

    private val warm = ClimateSettings(hvacOn = true, targetTempC = 26.0, fanSpeed = 6, acOn = false)

    @Test
    fun recorder_recordsOnlyInPark() {
        val r = SocRecorder()
        r.record(SocSample(0, 35.0), parked = true, climate = warm)
        r.record(SocSample(1000, 34.0), parked = false, climate = warm)
        assertTrue(r.samples().isEmpty())
        r.record(SocSample(2000, 34.0), parked = true, climate = warm)
        assertEquals(listOf(2000L), r.samples().map { it.timeMs })
    }

    @Test
    fun recorder_climateChangeStartsNewSegment() {
        val r = SocRecorder()
        r.record(SocSample(0, 35.0), true, warm)
        r.record(SocSample(1000, 34.0), true, warm)
        r.record(SocSample(2000, 34.0), true, warm.copy(targetTempC = 24.0))
        assertEquals(listOf(2000L), r.samples().map { it.timeMs })
        r.record(SocSample(3000, 33.0), true, warm.copy(targetTempC = 24.0, seatHeatLevels = listOf(2)))
        assertEquals(listOf(3000L), r.samples().map { it.timeMs })
    }

    @Test
    fun recorder_unreadableValueDoesNotResetSegment() {
        val r = SocRecorder()
        r.record(SocSample(0, 35.0), true, warm)
        r.record(SocSample(1000, 34.0), true, warm.copy(fanSpeed = null))
        assertEquals(2, r.samples().size)
    }
}
