package kr.appdevchallenge.evstay.domain

import java.util.Locale
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Model-based cabin load while parked. All coefficients come from [Assumptions]. */
object PriorDrainModel {
    /** Power for fully known settings; unknown values must be resolved before calling. */
    fun powerKw(climate: ClimateSettings, a: Assumptions): Double {
        var kw = a.baseLoadKw
        if (climate.hvacOn == true) {
            val target = climate.targetTempC ?: a.defaultTargetTempC
            kw += if (target >= a.outsideTempC) {
                (target - a.outsideTempC) * a.heatingKwPerDeg
            } else {
                (a.outsideTempC - target) * a.coolingKwPerDeg
            }
            kw += max(0, climate.fanSpeed ?: a.defaultFanSpeed) * a.fanKwPerStep
            if (climate.acOn == true) kw += a.acKw
        }
        kw += climate.seatHeatLevels.sumOf { max(0, it) } * a.seatHeatKwPerLevel
        return kw
    }

    fun percentPerHour(climate: ClimateSettings, capacityKwh: Double, a: Assumptions): Double =
        powerKw(climate, a) / capacityKwh * 100.0

    /**
     * Unreadable climate values are not guessed as "off": the low bound assumes the lighter
     * case and the high bound the heavier one. Returns (lowClimate, highClimate, notes).
     */
    fun bounds(c: ClimateSettings, a: Assumptions): Triple<ClimateSettings, ClimateSettings, List<String>> {
        val notes = mutableListOf<String>()
        val seatHigh = if (c.seatHeatUnknown) {
            notes += "앞좌석 열선 ${c.seatHeatUnknownSeats}석을 읽지 못해 그 좌석을 꺼짐~${a.unknownSeatHeatLevel}단 사이로 계산"
            c.seatHeatLevels + List(c.seatHeatUnknownSeats) { a.unknownSeatHeatLevel }
        } else c.seatHeatLevels
        if (c.hvacOn == null) {
            notes += "공조 전원을 읽지 못해 꺼짐~켜짐(${fmt(a.defaultTargetTempC)}°C·풍량 ${a.defaultFanSpeed}단) 사이로 계산"
            if (c.acOn == null) notes += "에어컨 상태를 읽지 못해 꺼짐~켜짐 사이로 계산"
            val off = c.copy(hvacOn = false)
            val on = c.copy(
                hvacOn = true,
                targetTempC = c.targetTempC ?: a.defaultTargetTempC,
                fanSpeed = c.fanSpeed ?: a.defaultFanSpeed,
                acOn = c.acOn ?: true,
                seatHeatLevels = seatHigh
            )
            return Triple(off, on, notes)
        }
        if (c.hvacOn == false) return Triple(c, c.copy(seatHeatLevels = seatHigh), notes)
        if (c.targetTempC == null) notes += "설정 온도를 읽지 못해 ${fmt(a.defaultTargetTempC)}°C로 가정"
        if (c.fanSpeed == null) notes += "풍량을 읽지 못해 ${a.defaultFanSpeed}단으로 가정"
        if (c.acOn == null) notes += "에어컨 상태를 읽지 못해 꺼짐~켜짐 사이로 계산"
        val low = c.copy(targetTempC = c.targetTempC ?: a.defaultTargetTempC, fanSpeed = c.fanSpeed ?: a.defaultFanSpeed, acOn = c.acOn ?: false)
        val high = low.copy(acOn = c.acOn ?: true, seatHeatLevels = seatHigh)
        return Triple(low, high, notes)
    }

    private fun fmt(v: Double) = if (v == v.toInt().toDouble()) v.toInt().toString() else String.format(Locale.ROOT, "%.1f", v)
}

/**
 * Pure stay-time estimator. No Android or SDK types: the adapters translate vehicle
 * signals into [StayInput] and render [StayEstimate].
 */
object StayEstimator {

    fun estimate(input: StayInput): StayEstimate {
        val a = input.assumptions
        val notes = mutableListOf<String>()

        val capacity = input.batteryCapacityKwh?.takeIf { it > 0 } ?: a.defaultCapacityKwh.also {
            notes += "배터리 용량을 읽지 못해 ${fmt1(it)}kWh로 가정"
        }
        val efficiency = input.efficiencyKmPerKwh?.takeIf { it > 0 } ?: a.defaultEfficiencyKmPerKwh

        val reserve = reserveLine(input, capacity, efficiency, notes)
        val soc = input.socPercent

        if (soc == null) {
            return StayEstimate(
                status = StayStatus.NO_DATA, socPercent = null, reserve = reserve,
                drainPercentPerHour = null, drainSource = null, measuredPercentPerHour = null,
                priorPercentPerHour = null, stayHours = null, comparisons = emptyList(),
                assumptions = notes + "배터리 잔량(SoC)을 아직 읽지 못함", warning = null
            )
        }

        val (climateLow, climateHigh, climateNotes) = PriorDrainModel.bounds(input.climate, a)
        val priorLow = PriorDrainModel.percentPerHour(climateLow, capacity, a)
        val priorHigh = PriorDrainModel.percentPerHour(climateHigh, capacity, a)
        val priorMid = (priorLow + priorHigh) / 2
        val partial = climateNotes.isNotEmpty()
        notes += "바깥 기온 ${fmt1(a.outsideTempC)}°C (사용자 입력, 기본 0°C). 공조 소비 모델은 실차 계측값이 아닌 근사치"
        notes += climateNotes

        val measurement = DrainMeter.measure(input.socSamples, input.nowMs)
        if (measurement is DrainMeasurement.Rising) {
            return StayEstimate(
                status = StayStatus.CHARGING, socPercent = soc, reserve = reserve,
                drainPercentPerHour = null, drainSource = null, measuredPercentPerHour = null,
                priorPercentPerHour = priorMid, stayHours = null, comparisons = emptyList(),
                assumptions = notes + "최근 잔량이 올라가 충전 중으로 판단, 소모 시간 계산 안 함",
                warning = null, partial = partial
            )
        }

        val measured = (measurement as? DrainMeasurement.Measured)?.percentPerHour
        val weight = (measurement as? DrainMeasurement.Measured)
            ?.let { min(1.0, it.spanPercent / a.fullTrustSpanPercent) } ?: 0.0
        fun blend(prior: Double) = if (measured != null) weight * measured + (1 - weight) * prior else prior
        val rateLow = blend(priorLow)
        val rateHigh = blend(priorHigh)
        val rate = (rateLow + rateHigh) / 2
        val source = when {
            measured == null -> DrainSource.PRIOR
            weight >= 1.0 -> DrainSource.MEASURED
            else -> DrainSource.BLENDED
        }
        val range = a.priorRangeFraction + (a.measuredRangeFraction - a.priorRangeFraction) * weight
        val rangeText = "가정 범위 ±${pct(range)}(미검증)"
        when (source) {
            DrainSource.PRIOR -> notes += "이번 측정 구간의 잔량 변화가 아직 적어(1%씩 ${DrainMeter.MIN_DROP_EVENTS}번 미만) 공조 설정 기반 추정, $rangeText"
            DrainSource.BLENDED -> notes += "이번 측정 구간의 잔량 변화와 공조 설정 추정을 섞어 계산, $rangeText"
            DrainSource.MEASURED -> notes += "이번 측정 구간의 잔량 변화 기반, $rangeText"
        }
        if (source != DrainSource.MEASURED) {
            val hoursToMeasured = DrainMeter.MIN_DROP_EVENTS / rate
            notes += "실측 전환에는 1%씩 ${DrainMeter.MIN_DROP_EVENTS}번 하락이 필요: 현재 소모율이면 최대 약 ${fmt1(hoursToMeasured)}시간" +
                if (hoursToMeasured > a.historyWindowHours) " — 기록 창(${fmt1(a.historyWindowHours)}시간)보다 길어 계속 설정 기반으로 추정될 수 있음" else ""
        }
        notes += "공조·열선 설정이 바뀌거나 P단을 벗어나면 측정 구간을 새로 시작"
        notes += "지금 설정을 그대로 유지한다고 가정"
        if (input.keepHvacOn) notes += "체류 조건: 난방(공조) 유지 — 공조를 끄는 대안은 비교에서 뺌"
        if (input.passengerPresent) notes += "체류 조건: 동승자 있음 — 조수석 열선을 끄는 대안은 제안하지 않음"
        input.minCabinTempC?.let {
            notes += "허용 최저 설정 온도 ${fmt1(it)}°C: 대안의 설정 온도를 그보다 낮추지 않고, 공조를 끄는 대안은 뺌(실제 실내 온도는 계산하지 않음)"
        }

        fun result(
            status: StayStatus,
            hours: HoursRange?,
            comparisons: List<SettingComparison>,
            warning: String?
        ) = StayEstimate(
            status = status, socPercent = soc, reserve = reserve,
            drainPercentPerHour = rate, drainSource = source, measuredPercentPerHour = measured,
            priorPercentPerHour = priorMid, stayHours = hours, comparisons = comparisons,
            assumptions = notes, warning = warning, partial = partial, rangeFraction = range,
            target = input.targetEndMs?.let {
                targetCheck((it - input.nowMs) / 3_600_000.0, hours, rateHigh * (1 + range), soc, reserve, capacity)
            }
        )

        if (reserve.homeStatus == HomeStatus.UNREACHABLE) {
            val need = reserve.homeNeedRawPercent ?: 0.0
            return result(
                StayStatus.HOME_UNREACHABLE, HoursRange(0.0, 0.0), emptyList(),
                "현재 가정상 귀가 잔량 부족: 집까지 약 ${need.roundToInt()}%가 필요한데 현재 ${soc.roundToInt()}%입니다. 충전을 검토하세요."
            )
        }
        if (soc <= reserve.totalPercent) {
            return result(
                StayStatus.BELOW_RESERVE, HoursRange(0.0, 0.0), emptyList(),
                "현재 가정상 귀가 예비선(${reserve.totalPercent.roundToInt()}%) 아래입니다. 출발하거나 충전을 검토하세요."
            )
        }

        val usable = soc - reserve.totalPercent
        val hours = HoursRange(
            low = usable / (rateHigh * (1 + range)),
            high = usable / (rateLow * (1 - range))
        )
        val comparisons = if (input.climate.hvacOn == true) {
            comparisons(input.climate, capacity, a, rate / priorMid, usable, rate, rateHigh * (1 + range), hours.low, input.minCabinTempC,
                keepHvacOn = input.keepHvacOn, passengerPresent = input.passengerPresent)
        } else emptyList()

        val nearReserve = usable < a.warnSocGapPercent || hours.low < a.warnLeadHours
        return result(
            if (nearReserve) StayStatus.WARNING else StayStatus.OK, hours, comparisons,
            if (nearReserve) "현재 가정상 곧 귀가 예비선(${reserve.totalPercent.roundToInt()}%)에 닿습니다. 설정을 줄이거나 출발을 준비하세요." else null
        )
    }

    /**
     * [lowEndRate] is the %/h behind the short end of the range. When short, the charge needed is
     * the SoC that, added now, lets the short end reach the planned time under the current setting:
     * reserve (unclamped home need + margin) + planned hours × lowEndRate − current SoC.
     */
    private fun targetCheck(
        targetHours: Double,
        hours: HoursRange?,
        lowEndRate: Double,
        soc: Double,
        reserve: ReserveLine,
        capacityKwh: Double
    ): TargetCheck {
        if (targetHours <= 0.0) return TargetCheck(0.0, met = true, marginMinutes = 0, reached = true)
        val low = hours?.low ?: 0.0
        val exact = (low - targetHours) * 60
        val met = exact >= 0.0
        // Display rounding must not turn a small shortfall into "0분 부족" or a margin.
        val shown = exact.roundToInt().let { if (!met && it >= 0) -1 else it }
        // Hold "충족 예상" when the home part is unconfirmed (navi) or missing altogether.
        val held = !reserve.homeVerified || reserve.homeStatus == HomeStatus.UNKNOWN
        if (met) return TargetCheck(targetHours, met = true, marginMinutes = shown, marginExactMinutes = exact, homeVerdictHeld = held)
        val reserveRaw = (reserve.homeNeedRawPercent ?: 0.0) + reserve.marginPercent
        val required = reserveRaw + targetHours * lowEndRate
        val charge = (required - soc).coerceAtLeast(0.0)
        return TargetCheck(
            targetHours, met = false, marginMinutes = shown, marginExactMinutes = exact,
            chargePercent = charge, chargeKwh = charge / 100.0 * capacityKwh, chargeBeyondFull = required > 100.0,
            homeVerdictHeld = held
        )
    }

    private fun reserveLine(
        input: StayInput,
        capacity: Double,
        efficiency: Double,
        notes: MutableList<String>
    ): ReserveLine {
        val a = input.assumptions
        val margin = max(0.0, input.userMarginPercent)
        val soc = input.socPercent
        val rejected = input.homeInputIssues.toMutableList()
        var chosen: HomeEnergy = HomeEnergy.Unknown
        var homeNeed: Double? = null
        for (candidate in input.homeCandidates) {
            val need: Double? = when (candidate) {
                // A negative value here is the arrival SoC re-based on the current SoC (the raw answer was
                // checked in HomeCandidates): need > current SoC, i.e. not enough to get home.
                is HomeEnergy.NaviSocAtDest -> when {
                    soc != null && candidate.socAtDestPercent > soc -> {
                        rejected += RejectedHome(label(candidate), "도착 시 예상 잔량이 현재 잔량보다 높음")
                        null
                    }
                    soc != null -> soc - candidate.socAtDestPercent
                    else -> null
                }
                is HomeEnergy.Distance -> {
                    if (!candidate.km.isFinite() || candidate.km < 0 || candidate.km > a.maxPlausibleHomeKm) {
                        rejected += RejectedHome(label(candidate), "${fmt1(candidate.km)}km는 국내 귀가 거리로 볼 수 없음(상한 ${fmt1(a.maxPlausibleHomeKm)}km)")
                        null
                    } else roadKm(candidate, a) / efficiency / capacity * 100.0 * a.homeTripBufferFactor
                }
                HomeEnergy.Unknown -> null
            }
            if (need != null) {
                chosen = candidate
                homeNeed = need
                break
            }
        }
        rejected.forEach { notes += "집 정보 제외(신뢰할 수 없음): ${it.label} — ${it.reason}" }
        when (val c = chosen) {
            is HomeEnergy.NaviSocAtDest -> notes += "집까지 필요한 잔량은 내비 경로의 도착 시 예상 잔량 기준"
            is HomeEnergy.Distance -> {
                if (c.source == DistanceSource.STRAIGHT_LINE) {
                    notes += "집까지 직선거리에 ${fmt1(a.straightLineToRoadFactor)}배를 곱해 도로 거리로 가정"
                }
                if (input.efficiencyKmPerKwh == null) notes += "전비를 읽지 못해 ${fmt1(efficiency)}km/kWh로 가정"
                else notes += "차량 전비 값은 단위를 확인하지 못해 범위 규칙으로 해석함(단위 미확인)"
                notes += "귀가 소모에 ${pct(a.homeTripBufferFactor - 1)} 여유를 더함. 저온 주행 전비 보정은 하지 않음(근거 자료 없음)" +
                    if (a.outsideTempC < a.coldDriveLimitC) " — 바깥 기온 ${fmt1(a.outsideTempC)}°C에서는 실제 귀가 소모가 이보다 클 수 있음" else ""
            }
            HomeEnergy.Unknown -> notes += "집 정보가 없어 귀가 보장 계산 불가 — 예비선은 사용자 여유분만으로 계산"
        }
        val status = when {
            homeNeed == null -> HomeStatus.UNKNOWN
            homeNeed > 100.0 || (soc != null && homeNeed > soc) -> HomeStatus.UNREACHABLE
            else -> HomeStatus.COMPUTED
        }
        val clampedNeed = homeNeed?.coerceIn(0.0, 100.0)
        val coldUncorrected = chosen is HomeEnergy.Distance && a.outsideTempC < a.coldDriveLimitC
        val homeVerified = when (val c = chosen) {
            is HomeEnergy.NaviSocAtDest -> c.verified
            is HomeEnergy.Distance -> c.verified
            HomeEnergy.Unknown -> true
        }
        if (!homeVerified) {
            notes += "내비 응답을 이 집의 경로로 확인하지 못함(응답 목적지 없음·직선거리 대조 불가) — 귀가 판단 보류. 설정에서 집까지 거리를 직접 입력하면 판단"
        }
        if (status == HomeStatus.UNKNOWN) {
            notes += "집까지 정보가 없어 예정 시각 충족 여부도 귀가 기준으로는 판단 보류(부족 쪽 판정은 표시)"
        }
        return ReserveLine(
            homeNeedPercent = clampedNeed,
            homeNeedRawPercent = homeNeed,
            marginPercent = margin,
            totalPercent = ((clampedNeed ?: 0.0) + margin).coerceIn(0.0, 100.0),
            homeSource = chosen,
            homeStatus = status,
            rejected = rejected,
            coldUncorrected = coldUncorrected,
            homeVerified = homeVerified
        )
    }

    fun label(h: HomeEnergy): String = when (h) {
        is HomeEnergy.NaviSocAtDest -> "내비 도착 예상 잔량 ${fmt1(h.socAtDestPercent)}%"
        is HomeEnergy.Distance -> when (h.source) {
            DistanceSource.NAVI_TRIP -> "내비 경로 거리 ${fmt1(h.km)}km"
            DistanceSource.MANUAL -> "직접 입력 거리 ${fmt1(h.km)}km"
            DistanceSource.STRAIGHT_LINE -> "즐겨찾기 집까지 직선거리 ${fmt1(h.km)}km"
        }
        HomeEnergy.Unknown -> "거리 없음"
    }

    private fun roadKm(d: HomeEnergy.Distance, a: Assumptions) =
        if (d.source == DistanceSource.STRAIGHT_LINE) d.km * a.straightLineToRoadFactor else d.km

    private fun comparisons(
        climate: ClimateSettings,
        capacity: Double,
        a: Assumptions,
        measuredScale: Double,
        usable: Double,
        currentRate: Double,
        currentLowEndRate: Double,
        currentLowHours: Double,
        minCabinTempC: Double?,
        keepHvacOn: Boolean,
        passengerPresent: Boolean
    ): List<SettingComparison> {
        if (climate.hvacOn != true) return emptyList()
        val target = climate.targetTempC ?: a.defaultTargetTempC
        val heating = target >= a.outsideTempC
        // A user-set minimum setpoint rules out alternatives whose setpoint goes below it, and
        // HVAC-off alternatives. The actual cabin temperature is neither read nor modelled.
        val lowerTempAllowed = minCabinTempC == null || target - 2 >= minCabinTempC
        val options = buildList {
            if (heating) { if (lowerTempAllowed) add("온도 2도 낮추면" to climate.copy(targetTempC = target - 2)) }
            else add("온도 2도 높이면" to climate.copy(targetTempC = target + 2))
            val fan = climate.fanSpeed
            if (fan != null && fan > 1) {
                val lower = max(1, fan - 2)
                add("풍량 ${fan}단→${lower}단(${fan - lower}단계 줄임)" to climate.copy(fanSpeed = lower))
                if (heating && lowerTempAllowed) {
                    add("온도 2도 낮추고 풍량 ${fan}단→${lower}단" to climate.copy(targetTempC = target - 2, fanSpeed = lower))
                }
            }
            // HVAC-off alternatives only when the user did not ask to keep heating (or set a setpoint floor).
            if (heating && minCabinTempC == null && !keepHvacOn) {
                if (passengerPresent) {
                    // A passenger is in the car: their seat heater is kept on (both front seats at 2).
                    add("공조 끄고 앞좌석 열선 2단씩(조수석 유지) 쓰면" to
                        climate.copy(hvacOn = false, seatHeatLevels = listOf(2, 2), seatHeatUnknownSeats = 0))
                } else {
                    // Driver seat at 2, passenger seat off, HVAC off: every seat is set, none unknown.
                    add("공조 끄고 운전석 열선 2단만(조수석 끔) 쓰면" to
                        climate.copy(hvacOn = false, seatHeatLevels = listOf(2), seatHeatUnknownSeats = 0))
                }
            }
        }
        val prior = { c: ClimateSettings -> PriorDrainModel.percentPerHour(c, capacity, a) }
        // The short end of the range scales with the rate the same way for the alternative.
        val lowScale = currentLowEndRate / currentRate
        return options.map { (label, alt) ->
            val altRate = prior(alt) * measuredScale
            val lowExact = (usable / (altRate * lowScale) - currentLowHours) * 60
            SettingComparison(
                label,
                roundTo5((usable / altRate - usable / currentRate) * 60),
                roundTo5(lowExact),
                lowExact,
                keepsHvacOn = alt.hvacOn == true
            )
        }.filter { it.deltaMinutes != 0 }
    }

    private fun roundTo5(minutes: Double): Int = (minutes / 5.0).roundToInt() * 5
    private fun fmt1(v: Double) = if (v == v.toInt().toDouble()) v.toInt().toString() else String.format(Locale.ROOT, "%.1f", v)
    private fun pct(fraction: Double) = "${(fraction * 100).roundToInt()}%"
}
