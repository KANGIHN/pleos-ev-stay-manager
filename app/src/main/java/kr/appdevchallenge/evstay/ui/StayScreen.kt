package kr.appdevchallenge.evstay.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kr.appdevchallenge.evstay.Labeled
import kr.appdevchallenge.evstay.UiState
import kr.appdevchallenge.evstay.ValueSource
import kr.appdevchallenge.evstay.domain.DistanceSource
import kr.appdevchallenge.evstay.domain.DrainSource
import kr.appdevchallenge.evstay.domain.HomeEnergy
import kr.appdevchallenge.evstay.domain.HomeStatus
import kr.appdevchallenge.evstay.domain.MonitorState
import kr.appdevchallenge.evstay.domain.SessionStatus
import kr.appdevchallenge.evstay.domain.SettingAdvice
import kr.appdevchallenge.evstay.domain.StayDecision
import kr.appdevchallenge.evstay.domain.StayEstimate
import kr.appdevchallenge.evstay.domain.StayStatus
import kr.appdevchallenge.evstay.navi.HomePlace
import kr.appdevchallenge.evstay.vehicle.GearState
import kr.appdevchallenge.evstay.vehicle.Reading
import kr.appdevchallenge.evstay.vehicle.Support
import java.util.Locale
import kotlin.math.roundToInt

class StayActions(
    val onDemo: (Boolean) -> Unit,
    val onMargin: (Double) -> Unit,
    val onManualKm: (Double) -> Unit,
    val onClearManualKm: () -> Unit,
    val onPickHome: (HomePlace) -> Unit,
    val onOutsideTemp: (Double) -> Unit,
    val onMinCabinTemp: (Double) -> Unit,
    val onClearMinCabinTemp: () -> Unit,
    val onStayConditions: (keepHvacOn: Boolean, minSetpointC: Double?, passengerPresent: Boolean) -> Unit,
    val onTarget: (Int) -> Unit,
    val onClearTarget: () -> Unit,
    val onStartSession: () -> Unit,
    val onStopSession: () -> Unit,
    val onRequestNotifications: () -> Unit
)

@Composable
fun StayScreen(ui: UiState, actions: StayActions) {
    Column(
        Modifier
            .fillMaxSize()
            .background(Palette.background)
            .padding(horizontal = 32.dp, vertical = 20.dp)
    ) {
        TopBar(ui, actions)
        Spacer(Modifier.height(16.dp))
        if (ui.driving) DrivingLine(ui) else ParkedContent(ui, actions)
    }
}

@Composable
private fun TopBar(ui: UiState, actions: StayActions) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text("정차 대기 배터리 매니저", fontSize = 28.sp, fontWeight = FontWeight.Bold, color = Palette.text)
        Spacer(Modifier.width(16.dp))
        if (ui.demo) Badge("시연 모드 · 시간 ×${ui.demoSpeed}", Palette.warn)
        Spacer(Modifier.weight(1f))
        Badge(gearText(ui.gear) + " · " + ui.gearSourceLabel, if (ui.driving) Palette.warn else Palette.neutral)
        if (!ui.driving) {
            Spacer(Modifier.width(20.dp))
            Text("시연 모드", color = Palette.textDim, fontSize = 18.sp)
            Spacer(Modifier.width(8.dp))
            Switch(checked = ui.demo, onCheckedChange = actions.onDemo)
        }
    }
}

/** While driving only one line is shown (distraction guideline). */
@Composable
private fun DrivingLine(ui: UiState) {
    val soc = ui.estimate?.socPercent?.roundToInt()
    val text = buildString {
        // On Pleos the system itself hides third-party screens in D; this line is the in-app policy
        // for platforms that keep the app visible.
        append("주행 중에는 대기 정보를 숨깁니다. P단에 정차하면 표시됩니다.")
        if (soc != null) append("  잔량 $soc%")
    }
    Card(Modifier.fillMaxWidth()) {
        Text(text, fontSize = 22.sp, color = Palette.text)
    }
}

@Composable
private fun ParkedContent(ui: UiState, actions: StayActions) {
    var showDetails by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(24.dp)) {
        Column(
            Modifier
                .weight(1.15f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            val e = ui.estimate
            if (e == null) Card(Modifier.fillMaxWidth()) { Text("차량 정보를 읽는 중…", color = Palette.textDim, fontSize = 20.sp) }
            else {
                DecisionCard(ui, e)
                ReserveCard(e)
                ComparisonCard(e)
            }
        }
        Column(
            Modifier
                .weight(1f)
                .fillMaxHeight()
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {
            SessionCard(ui, actions)
            SettingsCard(ui, actions)
            OutlinedButton(onClick = { showDetails = !showDetails }, modifier = Modifier.fillMaxWidth()) {
                Text(if (showDetails) "상세 접기" else "상세 보기 — 차량 신호·소모율·계산 가정", fontSize = 18.sp)
            }
            if (showDetails) {
                ui.estimate?.let { RateCard(it) }
                SignalsCard(ui)
                ui.estimate?.let { AssumptionsCard(it) }
            }
        }
    }
}

/**
 * First screen: the three decisions — until when, short of the planned time or not, and which
 * setting would cover it. Raw signals and assumptions are in the detail area.
 */
@Composable
private fun DecisionCard(ui: UiState, e: StayEstimate) {
    val color = statusColor(e.status)
    Card(Modifier.fillMaxWidth()) {
        // 1. Until when
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("몇 시까지 머물 수 있나", fontSize = 20.sp, color = Palette.textDim)
            Spacer(Modifier.width(10.dp))
            Tag(ValueSource.ESTIMATED)
        }
        Spacer(Modifier.height(6.dp))
        val h = e.stayHours
        val headline = when (e.status) {
            StayStatus.NO_DATA -> "잔량을 읽는 중"
            StayStatus.CHARGING -> "충전 중 — 소모 시간 계산 안 함"
            StayStatus.HOME_UNREACHABLE -> "현재 가정상 귀가 잔량 부족"
            StayStatus.BELOW_RESERVE -> "현재 가정상 예비선 아래"
            else -> h?.let {
                if (it.low >= 48) "48시간 이상"
                else "${clockAt(ui.nowMs, it.low, ui.nowMs)} ~ ${if (it.high >= 48) "48시간 이상" else clockAt(ui.nowMs, it.high, ui.nowMs)}까지"
            } ?: "-"
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(headline, fontSize = 48.sp, fontWeight = FontWeight.Bold, color = color)
            if (e.partial && h != null && e.status != StayStatus.HOME_UNREACHABLE) {
                Spacer(Modifier.width(14.dp))
                Badge("부분 추정", Palette.warn)
            }
        }
        val homeUnknown = e.reserve.homeStatus == HomeStatus.UNKNOWN
        when {
            e.status == StayStatus.HOME_UNREACHABLE -> Text(
                "집까지 필요한 잔량 약 ${(e.reserve.homeNeedRawPercent ?: 0.0).roundToInt()}% > 현재 ${e.socPercent?.roundToInt() ?: 0}% — 충전을 검토하세요",
                fontSize = 20.sp, color = Palette.danger, fontWeight = FontWeight.SemiBold
            )
            (e.status == StayStatus.OK || e.status == StayStatus.WARNING) && h != null -> Text(
                "약 ${hours(h.low)} ~ ${hours(h.high)} · 귀가 예비선 ${e.reserve.totalPercent.roundToInt()}%까지, 지금 설정 유지 시",
                fontSize = 18.sp, color = Palette.textDim
            )
        }
        if (homeUnknown) Text("귀가 보장 계산 불가 — 여유분 기준", fontSize = 18.sp, color = Palette.warn, fontWeight = FontWeight.SemiBold)
        if (!e.reserve.homeVerified) {
            Text("귀가 판단 보류 — 내비 응답 미확인 · 설정에서 '집까지 거리 직접 입력'을 쓰면 판단합니다",
                fontSize = 18.sp, color = Palette.warn, fontWeight = FontWeight.SemiBold)
        }
        if (e.reserve.coldUncorrected) {
            Text("저온 주행 보정 없음 — 바깥 기온 ${fmt1(ui.outsideTempC)}°C에서는 귀가 소모가 더 클 수 있음",
                fontSize = 16.sp, color = Palette.warn)
        }

        // 2. Short of the planned time?
        Spacer(Modifier.height(14.dp))
        Text("예정 시각까지 부족한가", fontSize = 20.sp, color = Palette.textDim)
        val t = e.target
        val (targetText, targetColor) = when {
            t == null -> "머물 예정 시각이 없습니다 — 오른쪽 설정에서 정하면 비교합니다" to Palette.textDim
            t.reached -> "머물 예정 시각 도달 — 대기 완료" to Palette.info
            t.met && t.homeVerdictHeld -> "${ui.targetEndMs?.let { dateTime(it) } ?: ""}까지 귀가 판단 보류 — " +
                (if (e.reserve.homeStatus == HomeStatus.UNKNOWN) "집까지 정보 없음" else "내비 응답 미확인") to Palette.warn
            t.met -> "${ui.targetEndMs?.let { dateTime(it) } ?: ""} 충족 예상 · 여유 약 ${minutes(t.marginMinutes)} (현재 가정, 범위의 짧은 쪽 기준)" to Palette.ok
            else -> "${ui.targetEndMs?.let { dateTime(it) } ?: ""}까지 약 ${minutes(-t.marginMinutes)} 부족 (현재 가정, 범위의 짧은 쪽 기준)" to Palette.danger
        }
        Text(targetText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = targetColor)

        // 3. Which setting would cover it?
        val advice = StayDecision.advise(t, e.comparisons)
        if (advice !is SettingAdvice.NoTarget && advice !is SettingAdvice.Reached) {
            Spacer(Modifier.height(14.dp))
            Text("어떤 설정이면 충족하나", fontSize = 20.sp, color = Palette.textDim)
            val (adviceText, adviceColor) = when (advice) {
                is SettingAdvice.AlreadyMet -> "지금 설정 그대로 충족 예상" to Palette.ok
                is SettingAdvice.HeldUnverifiedHome -> (if (e.reserve.homeStatus == HomeStatus.UNKNOWN)
                    "귀가 판단 보류 — 집까지 정보 없음 · '집까지 거리 직접 입력'을 쓰면 판단합니다"
                else "귀가 판단 보류 — 내비 응답 미확인 · '집까지 거리 직접 입력'을 쓰면 판단합니다") to Palette.warn
                is SettingAdvice.CoveredBy -> advice.options.joinToString("\n") {
                    "「${it.label}」 → 충족 예상 (짧은 쪽 기준 약 +${minutes(it.lowDeltaExactMinutes.roundToInt())}, 추정)"
                } to Palette.ok
                is SettingAdvice.NotCovered -> (advice.best?.let {
                    "비교한 설정 하나로는 부족 — 가장 큰 효과는 「${it.label}」 약 +${minutes(it.lowDeltaExactMinutes.roundToInt())}. 예정 시각을 앞당기거나 충전을 검토하세요"
                } ?: when (e.status) {
                    StayStatus.HOME_UNREACHABLE, StayStatus.BELOW_RESERVE -> "설정을 바꿔도 대기 시간이 생기지 않습니다 — 충전을 검토하세요"
                    else -> "비교할 공조 설정이 없습니다(공조 꺼짐 또는 일부 값 미확인) — 예정 시각을 앞당기거나 충전을 검토하세요"
                }) to Palette.warn
                else -> "" to Palette.textDim
            }
            Text(adviceText, fontSize = 20.sp, fontWeight = FontWeight.SemiBold, color = adviceColor)
            if (advice is SettingAdvice.NotCovered && t != null) {
                // Numbers instead of "앞당기거나 충전": when the short end reaches the reserve, and how much to charge.
                val canStay = (e.status == StayStatus.OK || e.status == StayStatus.WARNING) && h != null
                if (canStay && h != null) {
                    Text(
                        "· 지금 설정 그대로면 ${clockAt(ui.nowMs, h.low, ui.nowMs)}에 예비선 도달(짧은 쪽 기준) — 예정보다 약 ${minutes(advice.shortMinutes)} 일찍 출발",
                        fontSize = 18.sp, color = Palette.text
                    )
                }
                val pct = t.chargePercent
                val kwh = t.chargeKwh
                if (pct != null && kwh != null) {
                    Text(
                        if (t.chargeBeyondFull) "· 지금 설정으로는 100%까지 충전해도 예정 시각까지 부족(짧은 쪽 기준)"
                        else "· 지금 설정 그대로 예정 시각까지 머물려면 약 ${fmt1(pct)}%(약 ${fmt1(kwh)}kWh) 충전 필요(짧은 쪽 기준, 충전하는 동안의 소모는 제외)",
                        fontSize = 18.sp, color = Palette.text
                    )
                }
            }
            Text("이 앱은 차량 설정을 바꾸지 않습니다. 직접 조작해 주세요.", fontSize = 14.sp, color = Palette.textDim)
        }

        e.warning?.let {
            Spacer(Modifier.height(12.dp))
            Box(
                Modifier
                    .fillMaxWidth()
                    .background(Palette.danger.copy(alpha = 0.18f), RoundedCornerShape(10.dp))
                    .border(1.dp, Palette.danger, RoundedCornerShape(10.dp))
                    .padding(14.dp)
            ) { Text("⚠ $it", fontSize = 20.sp, color = Palette.text, fontWeight = FontWeight.SemiBold) }
        }
    }
}

/** Detail: how the drain rate was obtained. */
@Composable
private fun RateCard(e: StayEstimate) {
    Card(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("소모율", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
            Spacer(Modifier.width(10.dp))
            Tag(ValueSource.ESTIMATED)
        }
        val rate = e.drainPercentPerHour
        if (rate == null) {
            Text("계산할 수 있을 때 표시합니다.", fontSize = 16.sp, color = Palette.textDim)
        } else {
            Text("${fmt1(rate)}%/시간 · ${drainSourceText(e.drainSource)}", fontSize = 18.sp, color = Palette.text)
            e.measuredPercentPerHour?.let {
                Text("최근 잔량 변화 ${fmt1(it)}%/시간 (실측 기반) · 설정 기반 ${fmt1(e.priorPercentPerHour ?: 0.0)}%/시간 (추정)", fontSize = 15.sp, color = Palette.textDim)
            }
        }
    }
}

@Composable
private fun SessionCard(ui: UiState, actions: StayActions) {
    val view = SessionStatus.describe(ui.monitorState, ui.monitorError, ui.notificationsAllowed)
    var askConditions by remember { mutableStateOf(false) }
    var startAfter by remember { mutableStateOf(true) }
    if (askConditions) {
        StayConditionsDialog(
            ui,
            onConfirm = { keep, min, passenger ->
                actions.onStayConditions(keep, min, passenger)
                askConditions = false
                if (startAfter) actions.onStartSession()
            },
            onSkip = {
                askConditions = false
                if (startAfter) actions.onStartSession()
            },
            skipLabel = if (startAfter) "건너뛰고 시작" else "닫기",
            confirmLabel = if (startAfter) "이 조건으로 시작" else "저장"
        )
    }
    Card(Modifier.fillMaxWidth()) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
            Text(
                view.headline, fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                color = when {
                    view.active -> Palette.ok
                    ui.monitorState == MonitorState.FAILED || (ui.monitorState == MonitorState.RUNNING && !ui.notificationsAllowed) -> Palette.danger
                    else -> Palette.textDim
                },
                modifier = Modifier.weight(1f)
            )
            if (ui.sessionActive) OutlinedButton(onClick = actions.onStopSession) { Text("감시 종료") }
            else Button(onClick = { startAfter = true; askConditions = true }) {
                Text(if (ui.monitorState == MonitorState.FAILED) "다시 시도" else "정차 감시 시작")
            }
        }
        view.detail?.let { Text(it, fontSize = 16.sp, color = if (ui.notificationsAllowed) Palette.textDim else Palette.warn) }
        Row(Modifier.fillMaxWidth().padding(top = 6.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                "체류 조건: " + (if (ui.keepHvacOn) "난방 유지" + (ui.minCabinTempC?.let { " · 최저 설정 ${fmt1(it)}°C" } ?: "") else "난방 끄기도 비교") +
                    " · " + (if (ui.passengerPresent) "동승자 있음" else "동승자 없음"),
                fontSize = 16.sp, color = Palette.textDim, modifier = Modifier.weight(1f)
            )
            OutlinedButton(onClick = { startAfter = false; askConditions = true }) { Text("조건 바꾸기") }
        }
        if (!ui.notificationsAllowed) {
            OutlinedButton(onClick = actions.onRequestNotifications, modifier = Modifier.padding(top = 6.dp)) { Text("알림 권한 요청") }
        }
    }
}

/** One compact step before monitoring: what the user accepts during this stay. Skippable. */
@Composable
private fun StayConditionsDialog(
    ui: UiState,
    onConfirm: (keepHvacOn: Boolean, minSetpointC: Double?, passengerPresent: Boolean) -> Unit,
    onSkip: () -> Unit,
    skipLabel: String,
    confirmLabel: String
) {
    var keep by remember { mutableStateOf(ui.keepHvacOn) }
    var min by remember { mutableStateOf(ui.minCabinTempC) }
    var passenger by remember { mutableStateOf(ui.passengerPresent) }
    AlertDialog(
        onDismissRequest = onSkip,
        title = { Text("이번 정차의 조건", fontSize = 24.sp) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("난방(공조) 유지", fontSize = 20.sp, modifier = Modifier.weight(1f))
                    Switch(checked = keep, onCheckedChange = { keep = it })
                }
                if (keep) {
                    Stepper("대안의 최저 설정 온도", min?.let { "${fmt1(it)}°C" } ?: "안 씀",
                        { min = ((min ?: 21.0) - 1.0).coerceIn(10.0, 28.0) }, { min = ((min ?: 19.0) + 1.0).coerceIn(10.0, 28.0) })
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("동승자 있음", fontSize = 20.sp, modifier = Modifier.weight(1f))
                    Switch(checked = passenger, onCheckedChange = { passenger = it })
                }
                Text("제안할 설정 대안을 이 조건 안에서만 고릅니다. 난방 유지면 공조를 끄는 대안, 동승자가 있으면 조수석 열선을 끄는 대안을 빼고, " +
                    "설정 온도는 최저 설정 온도 아래로 내리지 않습니다(실제 실내 온도는 읽지 않음).",
                    fontSize = 15.sp, color = Palette.textDim)
            }
        },
        confirmButton = { Button(onClick = { onConfirm(keep, if (keep) min else null, passenger) }) { Text(confirmLabel) } },
        dismissButton = { OutlinedButton(onClick = onSkip) { Text(skipLabel) } }
    )
}

@Composable
private fun ReserveCard(e: StayEstimate) {
    val r = e.reserve
    Card(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("귀가 예비선 ${r.totalPercent.roundToInt()}%", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
            Spacer(Modifier.width(10.dp))
            Tag(ValueSource.ESTIMATED)
            Spacer(Modifier.weight(1f))
            e.socPercent?.let {
                Text("현재 ${it.roundToInt()}%", fontSize = 22.sp, color = Palette.text)
                Spacer(Modifier.width(8.dp))
                Tag(ValueSource.MEASURED)
            }
        }
        Spacer(Modifier.height(12.dp))
        ReserveBar(e)
        Spacer(Modifier.height(10.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
            Legend(Palette.reserveHome, "집까지 " + when (r.homeStatus) {
                HomeStatus.UNKNOWN -> "모름"
                HomeStatus.UNREACHABLE -> "약 ${(r.homeNeedRawPercent ?: 0.0).roundToInt()}% (현재 가정상 부족)"
                HomeStatus.COMPUTED -> "${fmt1(r.homeNeedPercent ?: 0.0)}%"
            } + " (${homeSourceText(r.homeSource)})")
            Legend(Palette.reserveMargin, "여유분 ${fmt1(r.marginPercent)}% (사용자 설정)")
        }
    }
}

@Composable
private fun ReserveBar(e: StayEstimate) {
    val soc = (e.socPercent ?: 0.0).coerceIn(0.0, 100.0)
    val home = (e.reserve.homeNeedPercent ?: 0.0).coerceIn(0.0, 100.0)
    val total = e.reserve.totalPercent.coerceIn(0.0, 100.0)
    var widthPx by remember { mutableIntStateOf(0) }
    val density = LocalDensity.current
    fun dpAt(percent: Double) = with(density) { (widthPx * percent / 100.0).toFloat().toDp() }
    Box(
        Modifier
            .fillMaxWidth()
            .height(40.dp)
            .background(Palette.surfaceHigh, RoundedCornerShape(8.dp))
            .onSizeChanged { widthPx = it.width }
    ) {
        val batteryColor = if (soc <= total) Palette.danger else Palette.battery
        Box(Modifier.width(dpAt(soc)).fillMaxHeight().background(batteryColor.copy(alpha = 0.55f), RoundedCornerShape(8.dp)))
        Box(Modifier.width(dpAt(home)).fillMaxHeight().background(Palette.reserveHome.copy(alpha = 0.55f)))
        Box(Modifier.offset(x = dpAt(home)).width(dpAt(total - home)).fillMaxHeight().background(Palette.reserveMargin.copy(alpha = 0.55f)))
        Box(Modifier.offset(x = dpAt(total)).width(4.dp).fillMaxHeight().background(Palette.danger))
    }
}

@Composable
private fun ComparisonCard(e: StayEstimate) {
    Card(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("설정을 바꾸면", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
            Spacer(Modifier.width(10.dp))
            Tag(ValueSource.ESTIMATED)
        }
        Spacer(Modifier.height(8.dp))
        if (e.comparisons.isEmpty()) {
            Text(
                when {
                    e.status != StayStatus.OK && e.status != StayStatus.WARNING -> "대기 시간을 계산할 수 있을 때 표시합니다."
                    e.partial -> "공조 상태를 일부 읽지 못해 비교하지 않습니다."
                    else -> "공조가 꺼져 있어 비교할 설정이 없습니다."
                },
                fontSize = 18.sp, color = Palette.textDim
            )
        }
        e.comparisons.forEach { c ->
            Row(Modifier.fillMaxWidth().padding(vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(c.label, fontSize = 20.sp, color = Palette.text, modifier = Modifier.weight(1f))
                val sign = if (c.deltaMinutes > 0) "+" else ""
                Text("약 $sign${minutes(c.deltaMinutes)} (추정, 중앙 소모율 기준)", fontSize = 20.sp, fontWeight = FontWeight.SemiBold,
                    color = if (c.deltaMinutes > 0) Palette.ok else Palette.warn)
            }
        }
        Text("이 앱은 차량 설정을 바꾸지 않습니다. 직접 조작해 주세요.", fontSize = 14.sp, color = Palette.textDim)
    }
}

@Composable
private fun SignalsCard(ui: UiState) {
    val v = ui.vehicle
    Card(Modifier.fillMaxWidth()) {
        Text("차량 신호", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
        if (!v.connected) Text(v.error ?: "차량 서비스 연결 중…", fontSize = 16.sp, color = Palette.warn)
        Spacer(Modifier.height(6.dp))
        SignalRow("배터리 잔량", v.soc) { "$it%" }
        LabeledRow("배터리 용량", ui.capacityKwh, unsupportedText = "모름 → 77.4kWh 가정") {
            "${fmt1(it)}kWh${raw(v.capacityRaw.value, "Wh · 단위 확인: 에뮬레이터 v2.1.0")}"
        }
        LabeledRow(
            "잔여 주행거리",
            ui.rangeKm?.let { Labeled(it, ValueSource.MEASURED) },
            unsupportedText = if (v.rangeRaw.value != null) "모름 (0 이하 보고)" else if (v.rangeRaw.support == Support.UNSUPPORTED) "지원 안 됨" else "읽는 중"
        ) { "${it.roundToInt()}km${raw(v.rangeRaw.value, "단위 미확인")}" }
        LabeledRow("전비", ui.efficiency, unsupportedText = "모름 → 5km/kWh 가정") { "${fmt1(it)}km/kWh${raw(v.efficiencyRaw.value, "단위 미확인")}" }
        SignalRow("공조 전원", v.hvacPower) { if (it) "켜짐" else "꺼짐" }
        SignalRow("설정 온도", v.targetTempC) { "${fmt1(it.toDouble())}°C" }
        SignalRow("풍량", v.fanSpeed) { "${it}단" }
        SignalRow("에어컨", v.acOn) { if (it) "켜짐" else "꺼짐" }
        SignalRow("운전석 열선", v.seatHeatDriver) { "${it}단" }
        SignalRow("조수석 열선", v.seatHeatPassenger) { "${it}단" }
        SignalRow("운전석 착석", v.driverOccupied) { if (it) "착석" else "비어 있음" }
        LabeledRow("집까지 거리", ui.homeDistanceKm, unsupportedText = "모름") {
            "${fmt1(it)}km" + if (ui.estimate?.reserve?.homeVerified == false) " (미확인)" else ""
        }
        ui.navi.trip?.let { trip ->
            Text(
                "내비 경로 거리 원시값 ${trip.distanceRaw} · 단위 미확인(미터로 해석)" +
                    if (trip.verifiedByDestination) " · 응답 목적지 일치" else " · 응답 목적지 없음",
                fontSize = 15.sp, color = Palette.textDim
            )
        }
        ui.tripIssue?.let { Text("신뢰할 수 없음 · $it", fontSize = 15.sp, color = Palette.warn) }
        ui.estimate?.reserve?.rejected?.forEach { r ->
            Text("신뢰할 수 없음 · ${r.label}: ${r.reason} → 다음 값 사용", fontSize = 15.sp, color = Palette.warn)
        }
        Text(
            when {
                ui.recording -> "이번 측정 구간 잔량 기록 ${ui.sampleCount}개 (변화 지점만 보관)"
                ui.gear == null -> "기어를 확인하지 못해 잔량을 기록하지 않음 (설정 기반 추정만)"
                else -> "P단이 아니어서 잔량을 기록하지 않음"
            },
            fontSize = 14.sp, color = Palette.textDim
        )
    }
}

@Composable
private fun SettingsCard(ui: UiState, actions: StayActions) {
    Card(Modifier.fillMaxWidth()) {
        Text("설정", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
        Spacer(Modifier.height(8.dp))
        Stepper("머물 예정 시각", ui.targetEndMs?.let { dateTime(it) } ?: "안 씀",
            { actions.onTarget(-30) }, { actions.onTarget(30) })
        if (ui.targetEndMs != null) {
            OutlinedButton(onClick = actions.onClearTarget) { Text("머물 예정 시각 끄기") }
        }
        Spacer(Modifier.height(8.dp))
        Stepper("바깥 기온 (직접 입력)", "${fmt1(ui.outsideTempC)}°C", { actions.onOutsideTemp(-2.0) }, { actions.onOutsideTemp(2.0) })
        Spacer(Modifier.height(8.dp))
        Stepper("대안의 최저 설정 온도", ui.minCabinTempC?.let { "${fmt1(it)}°C" } ?: "안 씀",
            { actions.onMinCabinTemp(-1.0) }, { actions.onMinCabinTemp(1.0) })
        if (ui.minCabinTempC != null) {
            OutlinedButton(onClick = actions.onClearMinCabinTemp) { Text("최저 설정 온도 끄기") }
            Text("설정 온도를 이보다 낮추는 대안과 공조를 끄는 대안은 제안하지 않습니다. 실제 실내 온도는 읽지 않습니다.", fontSize = 14.sp, color = Palette.textDim)
        }
        Spacer(Modifier.height(8.dp))
        Stepper("귀가 여유분", "${fmt1(ui.marginPercent)}%", { actions.onMargin(-1.0) }, { actions.onMargin(1.0) })
        Spacer(Modifier.height(8.dp))
        Stepper("집까지 거리 직접 입력", ui.manualHomeKm?.let { "${fmt1(it)}km" } ?: "안 씀",
            { actions.onManualKm(-10.0) }, { actions.onManualKm(10.0) })
        if (ui.manualHomeKm != null) {
            OutlinedButton(onClick = actions.onClearManualKm) { Text("직접 입력 끄기") }
        }
        Text("내비 경로가 있으면 내비 값을 먼저 씁니다.", fontSize = 14.sp, color = Palette.textDim)
        Spacer(Modifier.height(10.dp))
        val home = ui.home
        Text(
            when {
                home == null -> "집: 내비 즐겨찾기 '집'이 없습니다"
                home.fromBookmark -> "집: 내비 즐겨찾기"
                else -> "집: 최근 목적지에서 지정"
            } + (home?.name?.takeIf { it.isNotBlank() }?.let { " ($it)" } ?: ""),
            fontSize = 18.sp, color = Palette.text
        )
        if (ui.navi.available == false) Text(ui.navi.error ?: "내비 연동 불가", fontSize = 15.sp, color = Palette.warn)
        if (ui.navi.tripFailed) Text("내비 경로 예상 실패 → 거리 기반으로 계산", fontSize = 15.sp, color = Palette.warn)
        if (ui.navi.bookmarkHome == null && ui.navi.recentDestinations.isNotEmpty()) {
            Text("최근 목적지에서 집 고르기", fontSize = 15.sp, color = Palette.textDim)
            ui.navi.recentDestinations.forEach { place ->
                OutlinedButton(onClick = { actions.onPickHome(place) }, modifier = Modifier.padding(top = 4.dp)) {
                    Text(place.name.ifBlank { "이름 없는 장소" }, fontSize = 16.sp)
                }
            }
        }
    }
}

@Composable
private fun AssumptionsCard(e: StayEstimate) {
    Card(Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("계산 가정", fontSize = 22.sp, fontWeight = FontWeight.SemiBold, color = Palette.text)
            Spacer(Modifier.width(10.dp))
            Tag(ValueSource.ASSUMED)
        }
        Spacer(Modifier.height(6.dp))
        e.assumptions.forEach { Text("· $it", fontSize = 16.sp, color = Palette.textDim) }
    }
}

// ---- small components -------------------------------------------------------------------

@Composable
private fun Card(modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Column(
        modifier
            .background(Palette.surface, RoundedCornerShape(16.dp))
            .padding(20.dp)
    ) { content() }
}

@Composable
private fun Badge(text: String, color: Color) {
    Box(
        Modifier
            .background(color.copy(alpha = 0.2f), RoundedCornerShape(50))
            .border(1.dp, color, RoundedCornerShape(50))
            .padding(horizontal = 14.dp, vertical = 6.dp)
    ) { Text(text, color = color, fontSize = 17.sp, fontWeight = FontWeight.SemiBold) }
}

@Composable
private fun Tag(source: ValueSource) {
    val (text, color) = when (source) {
        ValueSource.MEASURED -> "실측" to Palette.ok
        ValueSource.ESTIMATED -> "추정" to Palette.warn
        ValueSource.NAVI -> "내비 예상" to Palette.info
        ValueSource.USER -> "사용자 설정" to Palette.neutral
        ValueSource.ASSUMED -> "가정" to Palette.neutral
    }
    Box(
        Modifier
            .border(1.dp, color, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) { Text(text, color = color, fontSize = 14.sp) }
}

@Composable
private fun UnsupportedTag() {
    Box(
        Modifier
            .border(1.dp, Palette.neutral, RoundedCornerShape(6.dp))
            .padding(horizontal = 8.dp, vertical = 2.dp)
    ) { Text("지원 안 됨", color = Palette.neutral, fontSize = 14.sp) }
}

@Composable
private fun <T> SignalRow(label: String, reading: Reading<T>, format: (T) -> String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 18.sp, color = Palette.textDim, modifier = Modifier.weight(1f))
        val value = reading.value
        when {
            value != null -> {
                Text(format(value), fontSize = 18.sp, color = Palette.text)
                Spacer(Modifier.width(8.dp))
                Tag(ValueSource.MEASURED)
            }
            reading.support == Support.UNSUPPORTED -> UnsupportedTag()
            else -> Text("읽는 중", fontSize = 16.sp, color = Palette.textDim)
        }
    }
}

@Composable
private fun LabeledRow(label: String, value: Labeled<Double>?, unsupportedText: String, format: (Double) -> String) {
    Row(Modifier.fillMaxWidth().padding(vertical = 3.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 18.sp, color = Palette.textDim, modifier = Modifier.weight(1f))
        val v = value?.value
        if (v != null) {
            Text(format(v), fontSize = 18.sp, color = Palette.text)
            Spacer(Modifier.width(8.dp))
            Tag(value.source)
        } else {
            Text(unsupportedText, fontSize = 16.sp, color = Palette.textDim)
        }
    }
}

@Composable
private fun Stepper(label: String, value: String, onMinus: () -> Unit, onPlus: () -> Unit) {
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 18.sp, color = Palette.text, modifier = Modifier.weight(1f))
        Button(onClick = onMinus, colors = ButtonDefaults.buttonColors(containerColor = Palette.surfaceHigh)) { Text("−", fontSize = 22.sp) }
        Text(value, fontSize = 20.sp, color = Palette.text, modifier = Modifier.padding(horizontal = 14.dp))
        Button(onClick = onPlus, colors = ButtonDefaults.buttonColors(containerColor = Palette.surfaceHigh)) { Text("+", fontSize = 22.sp) }
    }
}

@Composable
private fun Legend(color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.width(14.dp).height(14.dp).background(color, RoundedCornerShape(3.dp)))
        Spacer(Modifier.width(6.dp))
        Text(text, fontSize = 16.sp, color = Palette.textDim)
    }
}

// ---- formatting -------------------------------------------------------------------------

private fun statusColor(s: StayStatus) = when (s) {
    StayStatus.OK -> Palette.ok
    StayStatus.WARNING -> Palette.warn
    StayStatus.BELOW_RESERVE, StayStatus.HOME_UNREACHABLE -> Palette.danger
    StayStatus.CHARGING -> Palette.info
    StayStatus.NO_DATA -> Palette.textDim
}

private fun drainSourceText(s: DrainSource?) = when (s) {
    DrainSource.MEASURED -> "최근 잔량 변화 기반 (추정)"
    DrainSource.BLENDED -> "잔량 변화 + 공조 설정 혼합 (추정)"
    DrainSource.PRIOR -> "공조 설정 기반 (추정)"
    null -> ""
}

private fun homeSourceText(h: HomeEnergy) = when (h) {
    is HomeEnergy.NaviSocAtDest -> "내비 예상"
    is HomeEnergy.Distance -> when (h.source) {
        DistanceSource.NAVI_TRIP -> "내비 거리 기반 추정"
        DistanceSource.STRAIGHT_LINE -> "직선거리 기반 추정"
        DistanceSource.MANUAL -> "직접 입력 거리 기반 추정"
    }
    HomeEnergy.Unknown -> "거리 모름"
}

private fun gearText(g: GearState?) = when (g) {
    GearState.PARK -> "P단"
    GearState.DRIVE -> "D단"
    GearState.REVERSE -> "R단"
    GearState.NEUTRAL -> "N단"
    GearState.UNKNOWN, null -> "기어 모름"
}

private fun hours(h: Double): String = if (h >= 48) "48시간 이상" else "${fmt1(h)}시간"

private fun minutes(m: Int): String {
    val abs = kotlin.math.abs(m)
    val sign = if (m < 0) "-" else ""
    return if (abs >= 60) "$sign${abs / 60}시간 ${abs % 60}분" else "$sign${abs}분"
}

private fun fmt1(v: Double) = String.format(Locale.ROOT, "%.1f", v)

/** The raw SDK value is shown next to the interpreted one, with whether its unit was confirmed. */
private fun raw(value: Float?, unitNote: String): String =
    value?.let { " (원시값 ${String.format(Locale.ROOT, "%.1f", it)} · $unitNote)" } ?: ""

/** Wall-clock text of now + [hours] on the app clock; the date is added when it is not today. */
private fun clockAt(baseMs: Long, hours: Double, nowMs: Long): String {
    val at = baseMs + (hours * 3_600_000).toLong()
    val day = java.text.SimpleDateFormat("yyyyMMdd", Locale.KOREA)
    val sameDay = day.format(java.util.Date(at)) == day.format(java.util.Date(nowMs))
    return java.text.SimpleDateFormat(if (sameDay) "HH:mm" else "M/d HH:mm", Locale.KOREA).format(java.util.Date(at))
}

private fun dateTime(ms: Long): String = java.text.SimpleDateFormat("M/d HH:mm", Locale.KOREA).format(java.util.Date(ms))
