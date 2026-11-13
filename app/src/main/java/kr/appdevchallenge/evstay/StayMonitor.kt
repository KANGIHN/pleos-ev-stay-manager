package kr.appdevchallenge.evstay

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kr.appdevchallenge.evstay.domain.Assumptions
import kr.appdevchallenge.evstay.domain.ClimateSettings
import kr.appdevchallenge.evstay.domain.Coordinate
import kr.appdevchallenge.evstay.domain.DistanceSource
import kr.appdevchallenge.evstay.domain.HomeCandidates
import kr.appdevchallenge.evstay.domain.HomeEnergy
import kr.appdevchallenge.evstay.domain.MonitorState
import kr.appdevchallenge.evstay.domain.NotificationGate
import kr.appdevchallenge.evstay.domain.RestoreAction
import kr.appdevchallenge.evstay.domain.SessionRestore
import kr.appdevchallenge.evstay.domain.SessionStatus
import kr.appdevchallenge.evstay.domain.SeatHeat
import kr.appdevchallenge.evstay.domain.SeatHeatReading
import kr.appdevchallenge.evstay.domain.SocRecorder
import kr.appdevchallenge.evstay.domain.SocSample
import kr.appdevchallenge.evstay.domain.TripDistance
import kr.appdevchallenge.evstay.domain.TripSnapshot
import kr.appdevchallenge.evstay.domain.StayClock
import kr.appdevchallenge.evstay.domain.StayEstimate
import kr.appdevchallenge.evstay.domain.StayEstimator
import kr.appdevchallenge.evstay.domain.StayInput
import kr.appdevchallenge.evstay.domain.Units
import kr.appdevchallenge.evstay.navi.HomePlace
import kr.appdevchallenge.evstay.navi.HomeRouteSource
import kr.appdevchallenge.evstay.navi.NaviState
import kr.appdevchallenge.evstay.vehicle.CarSignals
import kr.appdevchallenge.evstay.vehicle.CarState
import kr.appdevchallenge.evstay.vehicle.GearState
import kr.appdevchallenge.evstay.vehicle.Support
import kr.appdevchallenge.evstay.vehicle.VehicleSignals
import kr.appdevchallenge.evstay.vehicle.VehicleSnapshot
import kotlin.math.abs

enum class ValueSource { MEASURED, NAVI, USER, ASSUMED, ESTIMATED }

data class Labeled<T>(val value: T?, val source: ValueSource)

data class UiState(
    val estimate: StayEstimate? = null,
    val vehicle: VehicleSnapshot = VehicleSnapshot(),
    val car: CarState = CarState(),
    val navi: NaviState = NaviState(),
    val gear: GearState? = null,
    val gearSourceLabel: String = "확인 중",
    val driving: Boolean = false,
    val demo: Boolean = false,
    val demoSpeed: Int = 60,
    val marginPercent: Double = DEFAULT_MARGIN,
    val manualHomeKm: Double? = null,
    val home: HomePlace? = null,
    val homeDistanceKm: Labeled<Double>? = null,
    val capacityKwh: Labeled<Double>? = null,
    val efficiency: Labeled<Double>? = null,
    val rangeKm: Double? = null,
    val sampleCount: Int = 0,
    val recording: Boolean = false,
    val outsideTempC: Double = 0.0,
    /** User-set lowest HVAC setpoint for suggested alternatives (not the cabin temperature); null = not set. */
    val minCabinTempC: Double? = null,
    /** Stay conditions asked when monitoring starts (skippable). Defaults keep the old behaviour. */
    val keepHvacOn: Boolean = false,
    val passengerPresent: Boolean = false,
    /** Planned end of this stay on the app clock (epoch ms); null = not set. */
    val targetEndMs: Long? = null,
    /** The user asked for a stay session (foreground service). Not proof that it runs: see [monitorState]. */
    val sessionActive: Boolean = false,
    /** Reported by the service: RUNNING only after startForeground succeeded. */
    val monitorState: MonitorState = MonitorState.OFF,
    val monitorError: String? = null,
    /** POST_NOTIFICATIONS granted and app notifications enabled. */
    val notificationsAllowed: Boolean = true,
    /** App clock at the last evaluation (accelerated in demo mode). */
    val nowMs: Long = 0L,
    /** Why the navi trip distance was not used, if rejected. */
    val tripIssue: String? = null
)

const val DEFAULT_MARGIN = 10.0

/**
 * Owns the vehicle/navi signal sources and the 2 s evaluation loop for the whole process, so
 * the loop keeps running while the screen is off or another app is shown. The UI
 * ([MainViewModel]) and [StayMonitorService] both read [ui]. One instance per process.
 */
class StayMonitor private constructor(private val app: Context) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val prefs = app.getSharedPreferences("evstay", Context.MODE_PRIVATE)
    private val clock = StayClock(System::currentTimeMillis)
    private val recorder = SocRecorder()
    private val notifier = StayNotifier(app)
    private val vehicle = VehicleSignals(app)
    private val car = CarSignals(app)
    private val navi = HomeRouteSource(app)

    private val _ui = MutableStateFlow(
        UiState(
            marginPercent = prefs.getFloat(KEY_MARGIN, DEFAULT_MARGIN.toFloat()).toDouble(),
            manualHomeKm = prefs.getFloat(KEY_MANUAL_KM, -1f).takeIf { it >= 0 }?.toDouble(),
            home = loadPickedHome(),
            demoSpeed = clock.demoSpeed,
            outsideTempC = prefs.getFloat(KEY_OUTSIDE, 0f).toDouble(),
            minCabinTempC = prefs.getFloat(KEY_MIN_CABIN, -100f).takeIf { it > -100f }?.toDouble(),
            keepHvacOn = prefs.getBoolean(KEY_KEEP_HVAC, false),
            passengerPresent = prefs.getBoolean(KEY_PASSENGER, false),
            targetEndMs = prefs.getLong(KEY_TARGET_END, -1L).takeIf { it >= 0 },
            sessionActive = prefs.getBoolean(KEY_SESSION, false),
            // A saved session is "starting" until it is resumed (service started again) or cleared;
            // see SessionRestore. It is never assumed to be running.
            monitorState = if (prefs.getBoolean(KEY_SESSION, false)) MonitorState.STARTING else MonitorState.OFF
        )
    )
    val ui: StateFlow<UiState> = _ui.asStateFlow()

    private var socAtTripRequest: Double? = null
    private var lastTripRequestRealMs = 0L
    private var lastHomeKey: String? = null
    @Volatile private var foreground = false
    private var targetReachedNotified = false
    private var serviceStartRequestedRealMs = System.currentTimeMillis()
    /** A session was saved before this process started and is not decided yet. */
    private var pendingRestore = prefs.getBoolean(KEY_SESSION, false)
    private val restoreSinceRealMs = System.currentTimeMillis()

    init {
        vehicle.start()
        car.start()
        navi.start()
        scope.launch {
            var tick = 0
            while (isActive) {
                vehicle.poll()
                if (tick == 2 || tick % 15 == 0) vehicle.recheckUnsupported()
                if (tick % 30 == 0) navi.refresh()
                recompute()
                tick++
                delay(POLL_MS)
            }
        }
    }

    fun setDemo(enabled: Boolean) {
        clock.setDemo(enabled)
        recorder.reset()
        _ui.update { it.copy(demo = enabled) }
        recompute()
    }

    fun changeMargin(delta: Double) {
        val m = (_ui.value.marginPercent + delta).coerceIn(0.0, 50.0)
        prefs.edit().putFloat(KEY_MARGIN, m.toFloat()).apply()
        _ui.update { it.copy(marginPercent = m) }
        recompute()
    }

    fun changeManualHomeKm(delta: Double) {
        val km = ((_ui.value.manualHomeKm ?: 0.0) + delta).coerceIn(0.0, 500.0)
        prefs.edit().putFloat(KEY_MANUAL_KM, km.toFloat()).apply()
        _ui.update { it.copy(manualHomeKm = km) }
        recompute()
    }

    fun clearManualHomeKm() {
        prefs.edit().remove(KEY_MANUAL_KM).apply()
        _ui.update { it.copy(manualHomeKm = null) }
        recompute()
    }

    fun setForeground(value: Boolean) {
        foreground = value
    }

    /**
     * Requests a stay session. The screen shows "감시 중" only after [StayMonitorService] reports
     * [onServiceStarted]; a refused start, a startForeground failure or no answer within
     * [SessionStatus.START_TIMEOUT_MS] is shown as a failure.
     */
    fun startSession() {
        val now = clock.nowMs()
        _ui.value.targetEndMs?.let { if (it <= now) clearTarget() }
        prefs.edit().putBoolean(KEY_SESSION, true).apply()
        recorder.reset()
        targetReachedNotified = false
        pendingRestore = false
        requestService()
        recompute()
    }

    /** Starts the foreground service; the outcome arrives via onServiceStarted / onServiceFailed. */
    private fun requestService() {
        serviceStartRequestedRealMs = System.currentTimeMillis()
        _ui.update { it.copy(sessionActive = true, monitorState = MonitorState.STARTING, monitorError = null) }
        try {
            ContextCompat.startForegroundService(app, Intent(app, StayMonitorService::class.java))
        } catch (e: Exception) {
            onServiceFailed("서비스 시작이 거부됨(${e.javaClass.simpleName})")
        }
    }

    /** Resumes or clears a session saved before this process started (e.g. after a force-stop). */
    private fun restoreSessionIfPending(parked: Boolean, gearKnown: Boolean, nowMs: Long) {
        if (!pendingRestore) return
        val s = _ui.value
        when (SessionRestore.decide(s.monitorState, parked, gearKnown, s.targetEndMs, nowMs,
            System.currentTimeMillis() - restoreSinceRealMs)) {
            RestoreAction.WAIT -> Unit
            RestoreAction.ALREADY_RUNNING -> pendingRestore = false
            RestoreAction.RESUME -> {
                pendingRestore = false
                requestService()
            }
            RestoreAction.CLEAR -> {
                pendingRestore = false
                prefs.edit().putBoolean(KEY_SESSION, false).apply()
                _ui.update { it.copy(sessionActive = false, monitorState = MonitorState.OFF, monitorError = null) }
            }
        }
    }

    fun stopSession() {
        pendingRestore = false
        prefs.edit().putBoolean(KEY_SESSION, false).apply()
        _ui.update { it.copy(sessionActive = false, monitorState = MonitorState.OFF, monitorError = null) }
        notifier.cancelAll()
        runCatching { app.stopService(Intent(app, StayMonitorService::class.java)) }
    }

    /** Called by the service after startForeground succeeded. */
    fun onServiceStarted() {
        if (!_ui.value.sessionActive) return
        _ui.update { it.copy(monitorState = MonitorState.RUNNING, monitorError = null) }
    }

    /** The service could not start or stopped on its own: the session is not running. */
    fun onServiceFailed(reason: String) {
        prefs.edit().putBoolean(KEY_SESSION, false).apply()
        _ui.update { it.copy(sessionActive = false, monitorState = MonitorState.FAILED, monitorError = reason) }
    }

    /** Called from the service's onDestroy. */
    fun onServiceStopped() {
        val s = _ui.value
        when {
            s.sessionActive -> onServiceFailed("감시 서비스가 종료됨")
            s.monitorState != MonitorState.FAILED -> _ui.update { it.copy(monitorState = MonitorState.OFF) }
        }
    }

    /** First press sets 20°C; then steps within 10–28°C. */
    fun changeMinCabinTemp(delta: Double) {
        val t = (_ui.value.minCabinTempC?.plus(delta) ?: 20.0).coerceIn(10.0, 28.0)
        prefs.edit().putFloat(KEY_MIN_CABIN, t.toFloat()).apply()
        _ui.update { it.copy(minCabinTempC = t) }
        recompute()
    }

    /** Stay conditions from the start dialog. Without kept heating the setpoint floor is cleared. */
    fun setStayConditions(keepHvacOn: Boolean, minSetpointC: Double?, passengerPresent: Boolean) {
        val floor = if (keepHvacOn) minSetpointC?.coerceIn(10.0, 28.0) else null
        prefs.edit()
            .putBoolean(KEY_KEEP_HVAC, keepHvacOn)
            .putBoolean(KEY_PASSENGER, passengerPresent)
            .apply {
                if (floor != null) putFloat(KEY_MIN_CABIN, floor.toFloat()) else remove(KEY_MIN_CABIN)
            }
            .apply()
        _ui.update { it.copy(keepHvacOn = keepHvacOn, passengerPresent = passengerPresent, minCabinTempC = floor) }
        recompute()
    }

    fun clearMinCabinTemp() {
        prefs.edit().remove(KEY_MIN_CABIN).apply()
        _ui.update { it.copy(minCabinTempC = null) }
        recompute()
    }

    fun changeOutsideTemp(delta: Double) {
        val t = (_ui.value.outsideTempC + delta).coerceIn(-30.0, 45.0)
        prefs.edit().putFloat(KEY_OUTSIDE, t.toFloat()).apply()
        _ui.update { it.copy(outsideTempC = t) }
        recompute()
    }

    /**
     * Moves the planned end of this stay by [deltaMinutes] (full date-time on the app clock).
     * The first press sets about two hours from now; it never goes earlier than 30 minutes from now.
     */
    fun changeTarget(deltaMinutes: Int) {
        val now = clock.nowMs()
        val current = _ui.value.targetEndMs
        val next = if (current == null || current <= now) {
            roundUpTo30Min(now + 2 * HOUR_MS)
        } else maxOf(roundUpTo30Min(now + 30 * MINUTE_MS), current + deltaMinutes * MINUTE_MS)
        prefs.edit().putLong(KEY_TARGET_END, next).apply()
        targetReachedNotified = false
        _ui.update { it.copy(targetEndMs = next) }
        recompute()
    }

    fun clearTarget() {
        prefs.edit().remove(KEY_TARGET_END).apply()
        _ui.update { it.copy(targetEndMs = null) }
        recompute()
    }

    fun nowAppMs(): Long = clock.nowMs()

    private fun roundUpTo30Min(ms: Long): Long = (ms + 30 * MINUTE_MS - 1) / (30 * MINUTE_MS) * (30 * MINUTE_MS)

    fun pickHome(place: HomePlace) {
        prefs.edit()
            .putString(KEY_HOME_NAME, place.name)
            .putFloat(KEY_HOME_LAT, place.latitude.toFloat())
            .putFloat(KEY_HOME_LON, place.longitude.toFloat())
            .putString(KEY_HOME_POI, place.poiId)
            .putString(KEY_HOME_POI_SUB, place.poiSubId)
            .apply()
        _ui.update { it.copy(home = place) }
        recompute()
    }

    private fun recompute() {
        val v = vehicle.state.value
        val c = car.state.value
        val n = navi.state.value
        val now = clock.nowMs()

        val soc = v.soc.value
        val socD = soc?.toDouble()

        val sdkGear = v.gear.takeIf { it.support == Support.SUPPORTED && it.value != null && it.value != GearState.UNKNOWN }
        val carGear = c.gear.takeIf { it.support == Support.SUPPORTED && it.value != null }
        val gear = sdkGear?.value ?: carGear?.value
        val gearLabel = when {
            sdkGear != null -> "차량 SDK"
            carGear != null -> "AAOS GEAR_SELECTION"
            else -> "기어 확인 불가"
        }
        val driving = c.distractionOptimizationRequired == true ||
            gear == GearState.DRIVE || gear == GearState.REVERSE || gear == GearState.NEUTRAL
        // SoC is recorded only with a confirmed P; without a gear signal nothing is recorded (model-only estimate).
        val parked = gear == GearState.PARK

        val (seatLevels, unknownSeats) = SeatHeat.split(
            listOf(v.seatHeatDriver, v.seatHeatPassenger).map { SeatHeatReading(it.value, it.support == Support.UNSUPPORTED) }
        )
        val climate = ClimateSettings(
            hvacOn = v.hvacPower.value,
            targetTempC = v.targetTempC.value?.toDouble(),
            fanSpeed = v.fanSpeed.value,
            acOn = v.acOn.value,
            seatHeatLevels = seatLevels,
            seatHeatUnknownSeats = unknownSeats
        )
        recorder.record(socD?.let { SocSample(now, it) }, parked, climate)

        val capacityMeasured = Units.capacityKwh(v.capacityRaw.value)
        val capacity = capacityMeasured?.let { Labeled(it, ValueSource.MEASURED) }
        val range = Units.rangeKm(v.rangeRaw.value)
        val efficiency = Units.efficiencyKmPerKwh(v.efficiencyRaw.value)?.let { Labeled(it, ValueSource.MEASURED) }
            ?: Units.efficiencyFromRange(range, socD, capacityMeasured)?.let { Labeled(it, ValueSource.ESTIMATED) }

        val state = _ui.value
        val home = n.bookmarkHome ?: state.home
        val homeKey = home?.let { "${it.latitude},${it.longitude},${it.poiId}" }
        if (homeKey != lastHomeKey) {
            // Destination changed: the cached route belongs to the old home.
            lastHomeKey = homeKey
            navi.clearTrip()
            lastTripRequestRealMs = 0L
            socAtTripRequest = null
        }
        requestTripIfNeeded(n, home, socD)

        val trip = n.trip
        val straightKm = if (home != null && n.currentLat != null && n.currentLon != null &&
            HomeCandidates.isValid(Coordinate(home.latitude, home.longitude)) &&
            HomeCandidates.isValid(Coordinate(n.currentLat, n.currentLon))
        ) Units.haversineKm(n.currentLat, n.currentLon, home.latitude, home.longitude) else null
        var tripIssue: String? = n.tripDiscardReason?.let { "내비 응답 제외: $it" }
        var tripDistanceChecked = false
        val tripKm = trip?.let {
            when (val d = TripDistance.interpret(it.distanceRaw, straightKm)) {
                is TripDistance.Reading.Km -> {
                    tripDistanceChecked = d.checked
                    d.km
                }
                is TripDistance.Reading.Rejected -> {
                    tripIssue = "내비 경로 거리 제외: ${d.reason}"
                    null
                }
            }
        }
        // A trip whose distance contradicts the requested home is not trusted at all.
        val trustedTrip = trip?.takeIf { tripKm != null }
        val homeInputs = HomeCandidates.build(
            trip = trustedTrip?.let {
                TripSnapshot(
                    "${it.place.latitude},${it.place.longitude},${it.place.poiId}", tripKm, it.estimatedSocAtDest, it.socAtRequest,
                    verifiedByDestination = it.verifiedByDestination,
                    distanceChecked = tripDistanceChecked,
                    ageMs = System.currentTimeMillis() - it.receivedAtRealMs
                )
            },
            tripFailed = n.tripFailed,
            homeKey = homeKey,
            home = home?.let { Coordinate(it.latitude, it.longitude) },
            current = if (n.currentLat != null && n.currentLon != null) Coordinate(n.currentLat, n.currentLon) else null,
            currentSoc = socD,
            manualKm = state.manualHomeKm
        )

        val estimate = StayEstimator.estimate(
            StayInput(
                nowMs = now,
                socPercent = socD,
                socSamples = recorder.samples(),
                batteryCapacityKwh = capacityMeasured,
                efficiencyKmPerKwh = efficiency?.value,
                climate = climate,
                homeCandidates = homeInputs.candidates,
                userMarginPercent = state.marginPercent,
                minCabinTempC = state.minCabinTempC,
                keepHvacOn = state.keepHvacOn,
                passengerPresent = state.passengerPresent,
                targetEndMs = state.targetEndMs,
                homeInputIssues = homeInputs.issues,
                assumptions = Assumptions(outsideTempC = state.outsideTempC)
            )
        )

        val homeKm: Labeled<Double>? = when (val chosen = estimate.reserve.homeSource) {
            is HomeEnergy.NaviSocAtDest -> tripKm?.let { Labeled(it, ValueSource.NAVI) }
            is HomeEnergy.Distance -> when (chosen.source) {
                DistanceSource.NAVI_TRIP -> Labeled(chosen.km, ValueSource.NAVI)
                DistanceSource.MANUAL -> Labeled(chosen.km, ValueSource.USER)
                DistanceSource.STRAIGHT_LINE -> Labeled(chosen.km, ValueSource.ESTIMATED)
            }
            HomeEnergy.Unknown -> null
        }

        val notificationsAllowed = notifier.canPost()
        val realNow = System.currentTimeMillis()
        restoreSessionIfPending(parked, gear != null && gear != GearState.UNKNOWN, now)
        if (!pendingRestore && SessionStatus.startTimedOut(_ui.value.monitorState, serviceStartRequestedRealMs, realNow)) {
            onServiceFailed("감시 서비스가 ${SessionStatus.START_TIMEOUT_MS / 1000}초 안에 시작되지 않음")
        }
        // Session end (car left P) is decided before any notification; notifications are only
        // posted while the monitoring service runs and P is confirmed.
        val current = _ui.value
        // A saved session still being decided is STARTING, so the gate posts nothing for it.
        val plan = NotificationGate.plan(
            monitorState = current.monitorState,
            sessionActive = current.sessionActive,
            driving = driving,
            parkConfirmed = parked,
            appInForeground = foreground,
            targetReached = estimate.target?.reached == true,
            targetAlreadyNotified = targetReachedNotified
        )
        if (plan.endSession) stopSession() // also cancels posted notifications
        if (plan.mayWarn) notifier.maybeNotify(estimate)
        if (plan.notifyTargetReached) {
            targetReachedNotified = true
            notifier.notifyTargetReached()
        }

        _ui.update {
            it.copy(
                estimate = estimate, vehicle = v, car = c, navi = n, gear = gear,
                gearSourceLabel = gearLabel, driving = driving, home = home,
                homeDistanceKm = homeKm, capacityKwh = capacity, efficiency = efficiency,
                rangeKm = range, sampleCount = recorder.samples().size, recording = parked,
                tripIssue = tripIssue, notificationsAllowed = notificationsAllowed, nowMs = now
            )
        }
    }

    /** Requests a navigation trip estimate home when stale or when SoC moved. */
    private fun requestTripIfNeeded(n: NaviState, home: HomePlace?, soc: Double?) {
        if (home == null || soc == null || n.available != true) return
        val realNow = System.currentTimeMillis()
        val stale = realNow - lastTripRequestRealMs > TRIP_REFRESH_MS
        val socMoved = socAtTripRequest?.let { abs(it - soc) >= 3 } ?: true
        if (stale || (socMoved && realNow - lastTripRequestRealMs > TRIP_MIN_GAP_MS)) {
            if (navi.requestTripTo(home, soc)) {
                lastTripRequestRealMs = realNow
                socAtTripRequest = soc
            }
        }
    }
    private fun loadPickedHome(): HomePlace? {
        val name = prefs.getString(KEY_HOME_NAME, null) ?: return null
        return HomePlace(
            name = name,
            latitude = prefs.getFloat(KEY_HOME_LAT, 0f).toDouble(),
            longitude = prefs.getFloat(KEY_HOME_LON, 0f).toDouble(),
            poiId = prefs.getString(KEY_HOME_POI, "").orEmpty(),
            poiSubId = prefs.getString(KEY_HOME_POI_SUB, "").orEmpty(),
            fromBookmark = false
        )
    }

    companion object {
        @Volatile private var instance: StayMonitor? = null

        fun get(context: Context): StayMonitor =
            instance ?: synchronized(this) {
                instance ?: StayMonitor(context.applicationContext).also { instance = it }
            }

        private const val MINUTE_MS = 60_000L
        private const val HOUR_MS = 3_600_000L
        const val POLL_MS = 2_000L
        const val TRIP_REFRESH_MS = 5 * 60_000L
        const val TRIP_MIN_GAP_MS = 20_000L
        const val KEY_OUTSIDE = "outside_temp"
        const val KEY_MIN_CABIN = "min_cabin_temp"
        const val KEY_KEEP_HVAC = "keep_hvac_on"
        const val KEY_PASSENGER = "passenger_present"
        const val KEY_TARGET_END = "target_end_ms"
        const val KEY_SESSION = "session_active"
        const val KEY_MARGIN = "margin"
        const val KEY_MANUAL_KM = "manual_home_km"
        const val KEY_HOME_NAME = "home_name"
        const val KEY_HOME_LAT = "home_lat"
        const val KEY_HOME_LON = "home_lon"
        const val KEY_HOME_POI = "home_poi"
        const val KEY_HOME_POI_SUB = "home_poi_sub"
    }
}
