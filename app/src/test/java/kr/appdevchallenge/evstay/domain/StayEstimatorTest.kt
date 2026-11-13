package kr.appdevchallenge.evstay.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.roundToInt

class StayEstimatorTest {

    private val hour = 3_600_000L
    private val winterClimate = ClimateSettings(hvacOn = true, targetTempC = 26.0, fanSpeed = 6, acOn = false)

    private fun input(
        soc: Double? = 35.0,
        samples: List<SocSample> = emptyList(),
        home: HomeEnergy = HomeEnergy.Distance(20.0, DistanceSource.NAVI_TRIP),
        climate: ClimateSettings = winterClimate,
        margin: Double = 10.0,
        capacity: Double? = 77.4,
        efficiency: Double? = 5.0,
        now: Long = 10 * hour
    ) = StayInput(
        nowMs = now, socPercent = soc, socSamples = samples, batteryCapacityKwh = capacity,
        efficiencyKmPerKwh = efficiency, climate = climate,
        homeCandidates = if (home == HomeEnergy.Unknown) emptyList() else listOf(home), userMarginPercent = margin
    )

    @Test
    fun noSamples_usesPriorWithWideRange() {
        val r = StayEstimator.estimate(input())
        assertEquals(StayStatus.OK, r.status)
        assertEquals(DrainSource.PRIOR, r.drainSource)
        assertNull(r.measuredPercentPerHour)
        val h = r.stayHours!!
        assertTrue(h.low < h.high)
        // ±25% prior uncertainty: high/low = 1.25/0.75
        assertEquals(1.25 / 0.75, h.high / h.low, 1e-6)
        assertTrue(r.assumptions.any { it.contains("공조 설정 기반") })
    }

    @Test
    fun noSoc_returnsNoData() {
        val r = StayEstimator.estimate(input(soc = null))
        assertEquals(StayStatus.NO_DATA, r.status)
        assertNull(r.stayHours)
    }

    @Test
    fun priorPower_matchesModel() {
        // 0.2 base + 26°C over 0°C * 0.08 + fan 6 * 0.03 = 2.46 kW
        assertEquals(2.46, PriorDrainModel.powerKw(winterClimate, Assumptions()), 1e-9)
        val off = ClimateSettings(hvacOn = false, seatHeatLevels = listOf(2, 0))
        assertEquals(0.3, PriorDrainModel.powerKw(off, Assumptions()), 1e-9)
    }

    @Test
    fun steadyDrops_switchToMeasuredRate() {
        // 1% every 30 min, 5 drops: 4% over 2 h = 2 %/h
        val samples = (0..5).map { SocSample(it * hour / 2, 40.0 - it) }
        val r = StayEstimator.estimate(input(soc = 35.0, samples = samples, now = 5 * hour / 2))
        assertEquals(DrainSource.MEASURED, r.drainSource)
        assertEquals(2.0, r.drainPercentPerHour!!, 1e-6)
        // usable = 35 - (20km/5/77.4*100*1.1 + 10)
        val reserve = 20.0 / 5.0 / 77.4 * 100 * 1.1 + 10.0
        assertEquals(reserve, r.reserve.totalPercent, 1e-6)
        assertEquals((35 - reserve) / (2.0 * 1.10), r.stayHours!!.low, 1e-6)
    }

    @Test
    fun fewDrops_blendMeasuredWithPrior() {
        val samples = listOf(SocSample(0, 37.0), SocSample(hour / 2, 36.0), SocSample(hour, 35.0), SocSample(hour * 3 / 2, 34.0))
        // 3 drop events: 36 → 34 over 1 h = 2 %/h, span 2% → weight 0.5
        val r = StayEstimator.estimate(input(soc = 34.0, samples = samples, now = hour * 3 / 2))
        assertEquals(DrainSource.BLENDED, r.drainSource)
        val prior = r.priorPercentPerHour!!
        assertEquals(0.5 * 2.0 + 0.5 * prior, r.drainPercentPerHour!!, 1e-6)
    }

    @Test
    fun stalledDrop_slowsMeasuredRate() {
        val samples = listOf(SocSample(0, 38.0), SocSample(hour / 2, 37.0), SocSample(hour, 36.0), SocSample(3 * hour / 2, 35.0))
        val fresh = DrainMeter.measure(samples, 3 * hour / 2) as DrainMeasurement.Measured
        val stalled = DrainMeter.measure(samples, 4 * hour) as DrainMeasurement.Measured
        assertEquals(2.0, fresh.percentPerHour, 1e-6)
        assertTrue(stalled.percentPerHour < fresh.percentPerHour)
    }

    @Test
    fun socRising_reportsCharging() {
        val samples = listOf(SocSample(0, 35.0), SocSample(hour / 2, 34.0), SocSample(hour, 35.0))
        val r = StayEstimator.estimate(input(soc = 35.0, samples = samples, now = hour))
        assertEquals(StayStatus.CHARGING, r.status)
        assertNull(r.stayHours)
        assertNull(r.warning)
    }

    @Test
    fun alreadyBelowReserve_warnsWithZeroTime() {
        val r = StayEstimator.estimate(input(soc = 12.0))
        assertEquals(StayStatus.BELOW_RESERVE, r.status)
        assertEquals(0.0, r.stayHours!!.high, 0.0)
        assertNotNull(r.warning)
        assertTrue(r.comparisons.isEmpty())
    }

    @Test
    fun nearReserve_warnsBeforeCrossing() {
        // reserve ≈ 15.68%; SoC 18 leaves ~2.3% → warning
        val r = StayEstimator.estimate(input(soc = 18.0))
        assertEquals(StayStatus.WARNING, r.status)
        assertNotNull(r.warning)
        assertTrue(r.stayHours!!.low > 0)
    }

    @Test
    fun missingHomeDistance_reserveIsMarginOnly() {
        val r = StayEstimator.estimate(input(home = HomeEnergy.Unknown, margin = 12.0))
        assertNull(r.reserve.homeNeedPercent)
        assertEquals(12.0, r.reserve.totalPercent, 0.0)
        assertEquals(HomeStatus.UNKNOWN, r.reserve.homeStatus)
        assertTrue(r.assumptions.any { it.contains("귀가 보장 계산 불가") })
    }

    @Test
    fun naviSocAtDest_definesHomeNeed() {
        val r = StayEstimator.estimate(input(soc = 35.0, home = HomeEnergy.NaviSocAtDest(29.0)))
        assertEquals(6.0, r.reserve.homeNeedPercent!!, 1e-9)
        assertEquals(16.0, r.reserve.totalPercent, 1e-9)
    }

    @Test
    fun invalidNaviSocAtDest_fallsBackToUnknown() {
        val r = StayEstimator.estimate(input(soc = 35.0, home = HomeEnergy.NaviSocAtDest(50.0)))
        assertNull(r.reserve.homeNeedPercent)
        assertEquals(HomeEnergy.Unknown, r.reserve.homeSource)
    }

    @Test
    fun straightLineDistance_appliesRoadFactor() {
        val r = StayEstimator.estimate(input(home = HomeEnergy.Distance(10.0, DistanceSource.STRAIGHT_LINE)))
        assertEquals(10.0 * 1.3 / 5.0 / 77.4 * 100 * 1.1, r.reserve.homeNeedPercent!!, 1e-9)
    }

    @Test
    fun missingCapacity_usesDefaultAndSaysSo() {
        val r = StayEstimator.estimate(input(capacity = null))
        assertTrue(r.assumptions.any { it.contains("77.4kWh") })
    }

    @Test
    fun comparisons_lowerTempGivesMoreTime() {
        val r = StayEstimator.estimate(input())
        val temp = r.comparisons.first { it.label.startsWith("온도 2도 낮추면") }
        assertTrue(temp.deltaMinutes > 0)
        assertEquals(0, temp.deltaMinutes % 5)
        val seatOnly = r.comparisons.first { it.label.contains("운전석 열선") }
        assertTrue(seatOnly.deltaMinutes > temp.deltaMinutes)
        assertTrue(r.comparisons.any { it.label.startsWith("풍량") })
    }

    @Test
    fun comparisons_coolingSuggestsRaisingTemp() {
        val hot = Assumptions(outsideTempC = 32.0)
        val r = StayEstimator.estimate(input(climate = winterClimate.copy(targetTempC = 22.0, acOn = true)).copy(assumptions = hot))
        assertTrue(r.comparisons.any { it.label.startsWith("온도 2도 높이면") && it.deltaMinutes > 0 })
        assertTrue(r.comparisons.none { it.label.contains("운전석 열선") })
    }

    @Test
    fun hvacOff_noComparisons() {
        val r = StayEstimator.estimate(input(climate = ClimateSettings(hvacOn = false)))
        assertTrue(r.comparisons.isEmpty())
        assertEquals(StayStatus.OK, r.status)
    }

    @Test
    fun demoScenario_35to22PercentCrossesIntoWarning() {
        val home = HomeEnergy.Distance(40.0, DistanceSource.MANUAL)
        val at35 = StayEstimator.estimate(input(soc = 35.0, home = home))
        assertEquals(StayStatus.OK, at35.status)
        assertEquals(21.37, at35.reserve.totalPercent, 0.01)
        assertEquals(3.43, at35.stayHours!!.low, 0.01)
        assertEquals(5.72, at35.stayHours!!.high, 0.01)
        assertEquals(20, at35.comparisons.first { it.label.startsWith("온도") }.deltaMinutes)

        val at22 = StayEstimator.estimate(input(soc = 22.0, home = home))
        assertEquals(StayStatus.WARNING, at22.status)

        val at20 = StayEstimator.estimate(input(soc = 20.0, home = home))
        assertEquals(StayStatus.BELOW_RESERVE, at20.status)
    }

    @Test
    fun bogusStraightLineDistance_isRejectedAndManualUsed() {
        // Emulator case: HOME bookmark 13190 km away from the current location.
        val bogus = HomeEnergy.Distance(13190.4, DistanceSource.STRAIGHT_LINE)
        val manual = HomeEnergy.Distance(40.0, DistanceSource.MANUAL)
        val r = StayEstimator.estimate(input(efficiency = null).copy(homeCandidates = listOf(bogus, manual)))
        assertEquals(manual, r.reserve.homeSource)
        assertEquals(1, r.reserve.rejected.size)
        assertTrue(r.reserve.rejected[0].label.contains("13190.4"))
        assertTrue(r.reserve.rejected[0].reason.contains("1500"))
        assertEquals(21.37, r.reserve.totalPercent, 0.01)
        assertEquals(StayStatus.OK, r.status)
    }

    @Test
    fun bogusDistanceOnly_fallsBackToMarginOnly() {
        val bogus = HomeEnergy.Distance(13190.4, DistanceSource.STRAIGHT_LINE)
        val r = StayEstimator.estimate(input(soc = 100.0).copy(homeCandidates = listOf(bogus)))
        assertEquals(HomeEnergy.Unknown, r.reserve.homeSource)
        assertNull(r.reserve.homeNeedPercent)
        assertEquals(10.0, r.reserve.totalPercent, 0.0)
        assertEquals(StayStatus.OK, r.status)
        assertTrue(r.assumptions.any { it.contains("신뢰할 수 없음") })
    }

    @Test
    fun longLegitimateRoute_isUnreachableNotRejected() {
        // 400 km needs ~114% SoC: a real long trip, not bad data.
        val unknown = StayEstimator.estimate(input(home = HomeEnergy.Unknown))
        val r = StayEstimator.estimate(input(home = HomeEnergy.Distance(400.0, DistanceSource.NAVI_TRIP)))
        assertTrue(r.reserve.rejected.isEmpty())
        assertEquals(HomeStatus.UNREACHABLE, r.reserve.homeStatus)
        assertEquals(StayStatus.HOME_UNREACHABLE, r.status)
        assertEquals(400.0 / 5.0 / 77.4 * 100 * 1.1, r.reserve.homeNeedRawPercent!!, 1e-9)
        assertEquals(100.0, r.reserve.homeNeedPercent!!, 0.0)
        assertTrue(r.warning!!.contains("충전"))
        assertTrue(r.warning!!.startsWith("현재 가정상 귀가 잔량 부족"))
        // Never a longer stay than the information-poor case.
        assertTrue(r.stayHours!!.high <= unknown.stayHours!!.low)
    }

    @Test
    fun homeNeedAboveCurrentSoc_isUnreachable() {
        // 200 km needs ~57% but only 35% is left.
        val r = StayEstimator.estimate(input(home = HomeEnergy.Distance(200.0, DistanceSource.MANUAL)))
        assertEquals(StayStatus.HOME_UNREACHABLE, r.status)
        assertEquals(0.0, r.stayHours!!.high, 0.0)
        assertTrue(r.comparisons.isEmpty())
    }

    @Test
    fun naviNegativeSocAtDest_isRejectedAndNextUsed() {
        val manual = HomeEnergy.Distance(40.0, DistanceSource.MANUAL)
        val r = StayEstimator.estimate(input().copy(homeCandidates = listOf(HomeEnergy.NaviSocAtDest(-1.0), manual)))
        assertEquals(manual, r.reserve.homeSource)
        assertTrue(r.reserve.rejected.single().reason.contains("음수"))
    }

    @Test
    fun unknownHvac_isPartialAndBracketsOffAndOn() {
        val unknown = StayEstimator.estimate(input(climate = ClimateSettings(hvacOn = null)))
        val off = StayEstimator.estimate(input(climate = ClimateSettings(hvacOn = false)))
        val on = StayEstimator.estimate(input(climate = ClimateSettings(hvacOn = true, targetTempC = 22.0, fanSpeed = 3, acOn = false)))
        assertTrue(unknown.partial)
        assertTrue(!off.partial)
        assertTrue(unknown.stayHours!!.low <= on.stayHours!!.low + 1e-9)
        assertTrue(unknown.stayHours!!.high >= off.stayHours!!.high - 1e-9)
        assertTrue(unknown.assumptions.any { it.contains("꺼짐~켜짐") })
        assertTrue(unknown.comparisons.isEmpty())
    }

    @Test
    fun outsideTemperature_changesPrior() {
        val cold = StayEstimator.estimate(input())
        val mild = StayEstimator.estimate(input().copy(assumptions = Assumptions(outsideTempC = 20.0)))
        assertTrue(mild.priorPercentPerHour!! < cold.priorPercentPerHour!!)
        assertTrue(mild.assumptions.any { it.contains("바깥 기온 20°C (사용자 입력") })
    }

    @Test
    fun rangeIsLabelledUnvalidated_andLowLoadSwitchTimeExplained() {
        val r = StayEstimator.estimate(input(climate = ClimateSettings(hvacOn = false)))
        assertTrue(r.assumptions.any { it.contains("가정 범위 ±25%(미검증)") })
        // 0.2 kW / 77.4 kWh = 0.258 %/h → 3 drops take ~11.6 h
        assertTrue(r.assumptions.any { it.contains("실측 전환") && it.contains("11.6") })
    }

    @Test
    fun targetTime_metShortAndReached() {
        val now = 10 * hour
        val base = input(home = HomeEnergy.Distance(40.0, DistanceSource.MANUAL), now = now)
        val met = StayEstimator.estimate(base.copy(targetEndMs = now + 3 * hour)).target!!
        assertTrue(met.met)
        assertEquals(((3.4314 - 3.0) * 60), met.marginMinutes.toDouble(), 1.0)
        val short = StayEstimator.estimate(base.copy(targetEndMs = now + 5 * hour)).target!!
        assertTrue(!short.met)
        assertTrue(short.marginMinutes < 0)
        val unreachable = StayEstimator.estimate(
            input(home = HomeEnergy.Distance(200.0, DistanceSource.MANUAL), now = now).copy(targetEndMs = now + hour)
        ).target!!
        assertEquals(-60, unreachable.marginMinutes)
        // At or after the planned end the stay is complete — never "24 hours short".
        val reached = StayEstimator.estimate(base.copy(targetEndMs = now)).target!!
        assertTrue(reached.reached)
        assertEquals(0, reached.marginMinutes)
        val past = StayEstimator.estimate(base.copy(targetEndMs = now - hour)).target!!
        assertTrue(past.reached)
    }

    @Test
    fun unknownHvacAndAc_highBoundAssumesAcOn() {
        val r = StayEstimator.estimate(input(climate = ClimateSettings(hvacOn = null, acOn = null)))
        val acOff = StayEstimator.estimate(input(climate = ClimateSettings(hvacOn = null, acOn = false)))
        assertTrue(r.stayHours!!.low < acOff.stayHours!!.low)
        assertTrue(r.assumptions.any { it.contains("에어컨 상태를 읽지 못해") })
    }

    @Test
    fun unknownSeatHeat_widensRange() {
        val known = StayEstimator.estimate(input(climate = ClimateSettings(hvacOn = false)))
        val unknown = StayEstimator.estimate(input(climate = ClimateSettings(hvacOn = false, seatHeatUnknownSeats = 2)))
        assertTrue(unknown.partial)
        assertTrue(unknown.stayHours!!.low < known.stayHours!!.low)
        assertEquals(known.stayHours!!.high, unknown.stayHours!!.high, 1e-9)
    }

    @Test
    fun reserveTotal_neverExceeds100() {
        val r = StayEstimator.estimate(input(soc = 100.0, home = HomeEnergy.Distance(300.0, DistanceSource.NAVI_TRIP), margin = 50.0))
        assertTrue(r.reserve.homeNeedPercent!! <= 100.0)
        assertEquals(100.0, r.reserve.totalPercent, 0.0)
    }

    @Test
    fun passengerSeatUnreadable_countsAsUnknown() {
        // Driver seat readable (off), passenger seat unreadable: the passenger load must be in the range.
        val (levels, unknown) = SeatHeat.split(listOf(SeatHeatReading(0, unreadable = false), SeatHeatReading(null, unreadable = true)))
        assertEquals(listOf(0), levels)
        assertEquals(1, unknown)
        val known = StayEstimator.estimate(input(climate = ClimateSettings(hvacOn = false, seatHeatLevels = listOf(0, 0))))
        val passengerUnknown = StayEstimator.estimate(
            input(climate = ClimateSettings(hvacOn = false, seatHeatLevels = levels, seatHeatUnknownSeats = unknown))
        )
        assertTrue(passengerUnknown.partial)
        assertTrue(passengerUnknown.stayHours!!.low < known.stayHours!!.low)
        assertTrue(passengerUnknown.assumptions.any { it.contains("1석을 읽지 못해") })
    }

    @Test
    fun seatSplit_driverUnreadable_andStillReading() {
        assertEquals(listOf(2) to 1, SeatHeat.split(listOf(SeatHeatReading(null, true), SeatHeatReading(2, false))))
        // Still being read for the first time, or supported but returning no value: unknown, not "off".
        assertEquals(emptyList<Int>() to 2, SeatHeat.split(listOf(SeatHeatReading(null, false), SeatHeatReading(null, false))))
        assertEquals(listOf(1) to 1, SeatHeat.split(listOf(SeatHeatReading(1, false), SeatHeatReading(null, false))))
    }

    @Test
    fun passengerSeatWithoutValue_widensRangeLikeUnsupported() {
        val (levels, unknown) = SeatHeat.split(listOf(SeatHeatReading(0, false), SeatHeatReading(null, unreadable = false)))
        val known = StayEstimator.estimate(input(climate = ClimateSettings(hvacOn = false, seatHeatLevels = listOf(0, 0))))
        val pending = StayEstimator.estimate(input(climate = ClimateSettings(hvacOn = false, seatHeatLevels = levels, seatHeatUnknownSeats = unknown)))
        assertTrue(pending.partial)
        assertTrue(pending.stayHours!!.low < known.stayHours!!.low)
    }

    @Test
    fun driverSeatOnlyScenario_turnsPassengerSeatOff() {
        val both = winterClimate.copy(seatHeatLevels = listOf(2, 3))
        val r = StayEstimator.estimate(input(climate = both))
        val seatOnly = r.comparisons.first { it.label.contains("운전석 열선 2단만") }
        assertTrue(seatOnly.label.contains("조수석 끔"))
        // Expected: HVAC off, driver 2, passenger off → 0.2 + 2 * 0.05 = 0.3 kW.
        val a = Assumptions()
        val usable = 35.0 - r.reserve.totalPercent
        val current = PriorDrainModel.percentPerHour(both, 77.4, a)
        val alt = 0.3 / 77.4 * 100.0
        val expected = ((usable / alt - usable / current) * 60 / 5.0).roundToInt() * 5
        assertEquals(expected, seatOnly.deltaMinutes)
    }

    @Test
    fun fanComparison_labelMatchesActualStepDifference() {
        val fan2 = StayEstimator.estimate(input(climate = winterClimate.copy(fanSpeed = 2)))
        assertTrue(fan2.comparisons.any { it.label == "풍량 2단→1단(1단계 줄임)" })
        val fan6 = StayEstimator.estimate(input(climate = winterClimate.copy(fanSpeed = 6)))
        assertTrue(fan6.comparisons.any { it.label == "풍량 6단→4단(2단계 줄임)" })
        val fan1 = StayEstimator.estimate(input(climate = winterClimate.copy(fanSpeed = 1)))
        assertTrue(fan1.comparisons.none { it.label.startsWith("풍량") })
    }

    @Test
    fun warningsSayTheyRestOnCurrentAssumptions() {
        val home = HomeEnergy.Distance(40.0, DistanceSource.MANUAL)
        assertTrue(StayEstimator.estimate(input(soc = 20.0, home = home)).warning!!.startsWith("현재 가정상"))
        assertTrue(StayEstimator.estimate(input(soc = 22.0, home = home)).warning!!.startsWith("현재 가정상"))
    }

    @Test
    fun coldOutsideWithDistanceHome_flagsMissingColdCorrection() {
        val home = HomeEnergy.Distance(40.0, DistanceSource.MANUAL)
        val cold = StayEstimator.estimate(input(home = home))
        assertTrue(cold.reserve.coldUncorrected)
        assertTrue(cold.assumptions.any { it.contains("실제 귀가 소모가 이보다 클 수 있음") })
        val mild = StayEstimator.estimate(input(home = home).copy(assumptions = Assumptions(outsideTempC = 20.0)))
        assertTrue(!mild.reserve.coldUncorrected)
        // Navigation's own arrival SoC is not a distance-based estimate: not flagged.
        val navi = StayEstimator.estimate(input(home = HomeEnergy.NaviSocAtDest(20.0)))
        assertTrue(!navi.reserve.coldUncorrected)
    }

    @Test
    fun vehicleEfficiency_isLabelledUnitUnconfirmed() {
        val r = StayEstimator.estimate(input(efficiency = 6.0))
        assertTrue(r.assumptions.any { it.contains("단위 미확인") })
        val assumed = StayEstimator.estimate(input(efficiency = null))
        assertTrue(assumed.assumptions.none { it.contains("단위 미확인") })
    }

    @Test
    fun comparisons_lowEndDeltaIsBelowMiddleDelta() {
        val r = StayEstimator.estimate(input())
        val temp = r.comparisons.first { it.label.startsWith("온도") }
        assertTrue(temp.lowDeltaMinutes in 1 until temp.deltaMinutes)
    }

    @Test
    fun scenarioA_fourHourTarget_onlyDriverSeatOnlyCoversTheGap() {
        // plan.md scenario A: 35%, 40 km manual, 26°C·fan 6, 0°C outside, target 4 h later.
        val now = 10 * hour
        val r = StayEstimator.estimate(
            input(home = HomeEnergy.Distance(40.0, DistanceSource.MANUAL), now = now).copy(targetEndMs = now + 4 * hour)
        )
        assertEquals(-34, r.target!!.marginMinutes)
        assertEquals(15, r.comparisons.first { it.label.startsWith("온도") }.lowDeltaMinutes)
        val advice = StayDecision.advise(r.target, r.comparisons) as SettingAdvice.CoveredBy
        assertEquals(34, advice.shortMinutes)
        assertEquals(listOf("공조 끄고 운전석 열선 2단만(조수석 끔) 쓰면"), advice.options.map { it.label })
        assertEquals(1480, advice.options.single().lowDeltaMinutes)
        // With a minimum setpoint set, the HVAC-off option is gone and nothing covers 34 min.
        val withMin = StayEstimator.estimate(
            input(home = HomeEnergy.Distance(40.0, DistanceSource.MANUAL), now = now)
                .copy(targetEndMs = now + 4 * hour, minCabinTempC = 20.0)
        )
        val notCovered = StayDecision.advise(withMin.target, withMin.comparisons) as SettingAdvice.NotCovered
        // plan.md page 1: best heating-on alternative, then leave-earlier / charge numbers.
        assertEquals("온도 2도 낮추고 풍량 6단→4단", notCovered.best!!.label)
        assertEquals(20, notCovered.best!!.lowDeltaExactMinutes.roundToInt())
        assertEquals(3.43, withMin.stayHours!!.low, 0.01)
        assertEquals(2.3, withMin.target!!.chargePercent!!, 0.05)
        assertEquals(1.75, withMin.target!!.chargeKwh!!, 0.01)
    }

    @Test
    fun targetJustShortByLessThanHalfMinute_isNotMet() {
        val now = 10 * hour
        val base = input(home = HomeEnergy.Distance(40.0, DistanceSource.MANUAL), now = now)
        val low = StayEstimator.estimate(base).stayHours!!.low
        // Target 20 s beyond the short end: rounds to 0 minutes but is still short.
        val t = StayEstimator.estimate(base.copy(targetEndMs = now + (low * hour).toLong() + 20_000)).target!!
        assertTrue(!t.met)
        assertTrue(t.marginExactMinutes < 0)
        assertEquals(-1, t.marginMinutes)
    }

    @Test
    fun minCabinTemp_removesLowerTempAndHvacOffAlternatives() {
        val free = StayEstimator.estimate(input())
        assertTrue(free.comparisons.any { it.label == "온도 2도 낮추고 풍량 6단→4단" })
        assertTrue(free.comparisons.any { it.label.contains("공조 끄고") })
        val min25 = StayEstimator.estimate(input().copy(minCabinTempC = 25.0))
        assertTrue(min25.comparisons.none { it.label.contains("온도 2도 낮추") })
        assertTrue(min25.comparisons.none { it.label.contains("공조 끄고") })
        assertTrue(min25.comparisons.any { it.label.startsWith("풍량") })
        assertTrue(min25.assumptions.any { it.contains("허용 최저 설정 온도 25°C") && it.contains("실제 실내 온도는 계산하지 않음") })
        val min24 = StayEstimator.estimate(input().copy(minCabinTempC = 24.0))
        assertTrue(min24.comparisons.any { it.label == "온도 2도 낮추면" })
        assertTrue(min24.comparisons.none { it.label.contains("공조 끄고") })
    }

    @Test
    fun combinedAlternative_gainsMoreThanEitherPart() {
        val r = StayEstimator.estimate(input())
        val temp = r.comparisons.first { it.label == "온도 2도 낮추면" }
        val fan = r.comparisons.first { it.label.startsWith("풍량") }
        val both = r.comparisons.first { it.label.startsWith("온도 2도 낮추고") }
        assertTrue(both.lowDeltaExactMinutes > temp.lowDeltaExactMinutes)
        assertTrue(both.lowDeltaExactMinutes > fan.lowDeltaExactMinutes)
    }

    @Test
    fun scenarioA_short34min_chargeNeededToReachPlannedTime() {
        val now = 10 * hour
        val base = input(home = HomeEnergy.Distance(40.0, DistanceSource.MANUAL), now = now)
        val r = StayEstimator.estimate(base.copy(targetEndMs = now + 4 * hour))
        val t = r.target!!
        // Short end uses rate × 1.25; required = reserve + 4 h × that rate.
        val lowRate = PriorDrainModel.percentPerHour(winterClimate, 77.4, Assumptions()) * 1.25
        val expected = r.reserve.totalPercent + 4 * lowRate - 35.0
        assertEquals(expected, t.chargePercent!!, 1e-9)
        assertEquals(2.26, t.chargePercent!!, 0.01)
        assertEquals(t.chargePercent!! / 100 * 77.4, t.chargeKwh!!, 1e-9)
        assertTrue(!t.chargeBeyondFull)
        // With exactly that much more SoC the short end reaches the planned time.
        val charged = StayEstimator.estimate(base.copy(socPercent = 35.0 + t.chargePercent!! + 1e-6, targetEndMs = now + 4 * hour))
        assertTrue(charged.target!!.met)
        assertNull(charged.target!!.chargePercent)
    }

    @Test
    fun chargeNeed_usesUnclampedHomeNeed_andFlagsBeyondFull() {
        val now = 10 * hour
        val unreachable = StayEstimator.estimate(
            input(home = HomeEnergy.Distance(200.0, DistanceSource.MANUAL), now = now).copy(targetEndMs = now + hour)
        )
        val lowRate = PriorDrainModel.percentPerHour(winterClimate, 77.4, Assumptions()) * 1.25
        val need = unreachable.reserve.homeNeedRawPercent!! + 10.0
        assertEquals(need + lowRate - 35.0, unreachable.target!!.chargePercent!!, 1e-9)
        assertTrue(!unreachable.target!!.chargeBeyondFull)
        val tooFar = StayEstimator.estimate(
            input(home = HomeEnergy.Distance(400.0, DistanceSource.NAVI_TRIP), now = now).copy(targetEndMs = now + hour)
        )
        assertTrue(tooFar.target!!.chargeBeyondFull)
        // The displayed reserve is clamped to 100%; the charge need is not.
        assertEquals(tooFar.reserve.homeNeedRawPercent!! + 10.0 + lowRate - 35.0, tooFar.target!!.chargePercent!!, 1e-9)
    }

    @Test
    fun unverifiedNaviHome_holdsMetVerdict_andManualRestoresIt() {
        val now = 10 * hour
        val navi = HomeEnergy.Distance(40.0, DistanceSource.NAVI_TRIP, verified = false)
        val held = StayEstimator.estimate(input(home = navi, now = now).copy(targetEndMs = now + 3 * hour))
        assertTrue(!held.reserve.homeVerified)
        assertTrue(held.target!!.met)
        assertTrue(held.target!!.homeVerdictHeld)
        assertEquals(SettingAdvice.HeldUnverifiedHome, StayDecision.advise(held.target, held.comparisons))
        assertTrue(held.assumptions.any { it.contains("귀가 판단 보류") })
        // Short and covered only by a setting change: also held, not "충족 예상".
        val short = StayEstimator.estimate(input(home = navi, now = now).copy(targetEndMs = now + 4 * hour))
        assertEquals(SettingAdvice.HeldUnverifiedHome, StayDecision.advise(short.target, short.comparisons))
        // The same distance entered directly is trusted.
        val manual = StayEstimator.estimate(input(home = HomeEnergy.Distance(40.0, DistanceSource.MANUAL), now = now).copy(targetEndMs = now + 3 * hour))
        assertTrue(manual.reserve.homeVerified)
        assertTrue(StayDecision.advise(manual.target, manual.comparisons) is SettingAdvice.AlreadyMet)
    }

    @Test
    fun stayConditions_constrainAlternatives() {
        val seats = winterClimate.copy(seatHeatLevels = listOf(1, 1))
        val free = StayEstimator.estimate(input(climate = seats))
        assertTrue(free.comparisons.any { it.label.contains("조수석 끔") })
        // Heating kept: no HVAC-off alternative at all.
        val keep = StayEstimator.estimate(input(climate = seats).copy(keepHvacOn = true))
        assertTrue(keep.comparisons.none { it.label.contains("공조 끄고") })
        assertTrue(keep.comparisons.all { it.keepsHvacOn })
        assertTrue(keep.assumptions.any { it.contains("난방(공조) 유지") })
        // Passenger present: the passenger seat heater is never turned off.
        val passenger = StayEstimator.estimate(input(climate = seats).copy(passengerPresent = true))
        assertTrue(passenger.comparisons.none { it.label.contains("조수석 끔") })
        val both = passenger.comparisons.first { it.label.contains("조수석 유지") }
        // 0.2 base + 2 seats × 2 × 0.05 = 0.4 kW, i.e. less gain than the driver-only variant.
        assertTrue(both.lowDeltaExactMinutes < free.comparisons.first { it.label.contains("조수석 끔") }.lowDeltaExactMinutes)
    }

    @Test
    fun defaultStayConditions_keepScenarioA() {
        val now = 10 * hour
        val r = StayEstimator.estimate(
            input(home = HomeEnergy.Distance(40.0, DistanceSource.MANUAL), now = now).copy(targetEndMs = now + 4 * hour)
        )
        val advice = StayDecision.advise(r.target, r.comparisons) as SettingAdvice.CoveredBy
        assertEquals(listOf("공조 끄고 운전석 열선 2단만(조수석 끔) 쓰면"), advice.options.map { it.label })
    }
}
