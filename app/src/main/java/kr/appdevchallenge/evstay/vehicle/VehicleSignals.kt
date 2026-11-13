package kr.appdevchallenge.evstay.vehicle

import ai.pleos.playground.vehicle.Vehicle
import ai.pleos.playground.vehicle.api.model.area.SeatArea
import ai.pleos.playground.vehicle.api.model.capability.AccessType
import ai.pleos.playground.vehicle.api.model.capability.CapabilityConfig
import ai.pleos.playground.vehicle.constant.area.SeatValue
import ai.pleos.playground.vehicle.constant.control.VehicleGear
import ai.pleos.playground.vehicle.constant.unit.TemperatureUnit
import ai.pleos.playground.vehicle.listener.EvBatteryEfficiencyListener
import ai.pleos.playground.vehicle.listener.EvBatteryStateOfChargeListener
import ai.pleos.playground.vehicle.listener.GearSelectionListener
import ai.pleos.playground.vehicle.listener.HvacAcStateListener
import ai.pleos.playground.vehicle.listener.HvacFanSpeedListener
import ai.pleos.playground.vehicle.listener.HvacPowerStateListener
import ai.pleos.playground.vehicle.listener.HvacSeatTemperatureListener
import ai.pleos.playground.vehicle.listener.HvacTemperatureListener
import ai.pleos.playground.vehicle.listener.RangeRemainingListener
import ai.pleos.playground.vehicle.listener.SeatOccupancyListener
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

enum class Support { PENDING, SUPPORTED, UNSUPPORTED }

data class Reading<out T>(val value: T? = null, val support: Support = Support.PENDING)

enum class GearState { PARK, DRIVE, REVERSE, NEUTRAL, UNKNOWN }

data class VehicleSnapshot(
    val connected: Boolean = false,
    val error: String? = null,
    val soc: Reading<Int> = Reading(),
    val capacityRaw: Reading<Float> = Reading(),
    val rangeRaw: Reading<Float> = Reading(),
    val efficiencyRaw: Reading<Float> = Reading(),
    val hvacPower: Reading<Boolean> = Reading(),
    val targetTempC: Reading<Float> = Reading(),
    val fanSpeed: Reading<Int> = Reading(),
    val acOn: Reading<Boolean> = Reading(),
    val seatHeatDriver: Reading<Int> = Reading(),
    val seatHeatPassenger: Reading<Int> = Reading(),
    val driverOccupied: Reading<Boolean> = Reading(),
    val gear: Reading<GearState> = Reading()
)

/**
 * Read-only adapter over the Pleos Vehicle SDK. Every signal is capability-checked first
 * (where the SDK offers a check); a failed check or call marks the signal UNSUPPORTED and
 * never throws out of this class. Listeners give immediate updates; [poll] re-reads values.
 */
class VehicleSignals(context: Context) {

    private val appContext = context.applicationContext
    private var vehicle: Vehicle? = null
    private val _state = MutableStateFlow(VehicleSnapshot())
    val state: StateFlow<VehicleSnapshot> = _state.asStateFlow()

    private var hvacPowerArea: SeatArea? = null
    private var hvacTempArea: SeatArea? = null
    private var hvacFanArea: SeatArea? = null
    private var hvacAcArea: SeatArea? = null
    private var seatHeatDriverArea: SeatArea? = null
    private var seatHeatPassengerArea: SeatArea? = null
    private var occupancyArea: SeatArea? = null
    private val registered = mutableListOf<() -> Unit>()

    fun start() {
        if (vehicle != null) return
        try {
            val v = Vehicle(appContext)
            v.initialize()
            vehicle = v
            _state.update { it.copy(connected = true, error = null) }
            checkCapabilities(v)
        } catch (e: Exception) {
            Log.w(TAG, "Vehicle initialize failed", e)
            vehicle = null
            _state.update { it.copy(connected = false, error = "차량 서비스 연결 실패: ${e.javaClass.simpleName}") }
        }
    }

    /** Re-reads every supported value; retries capability checks for signals not yet supported. */
    fun poll() {
        val v = vehicle ?: run { start(); return }
        val s = _state.value
        if (s.soc.support == Support.SUPPORTED) readSoc(v)
        if (s.capacityRaw.support != Support.UNSUPPORTED) readCapacity(v)
        if (s.rangeRaw.support == Support.SUPPORTED) readRange(v)
        if (s.efficiencyRaw.support == Support.SUPPORTED) readEfficiency(v)
        hvacPowerArea?.let { readHvacPower(v, it) }
        hvacTempArea?.let { readTemp(v, it) }
        hvacFanArea?.let { readFan(v, it) }
        hvacAcArea?.let { readAc(v, it) }
        seatHeatDriverArea?.let { a -> readSeatHeat(v, a) { r -> copy(seatHeatDriver = r) } }
        seatHeatPassengerArea?.let { a -> readSeatHeat(v, a) { r -> copy(seatHeatPassenger = r) } }
        occupancyArea?.let { readOccupancy(v, it) }
        if (s.gear.support == Support.SUPPORTED) readGear(v)
    }

    /** Capability checks can fail while the service is still binding; retry those signals. */
    fun recheckUnsupported() {
        val v = vehicle ?: return
        val s = _state.value
        val u = Support.UNSUPPORTED
        if (s.soc.support == u) checkSoc(v)
        if (s.capacityRaw.support == u) readCapacity(v)
        if (s.rangeRaw.support == u) readRange(v, listen = true)
        if (s.efficiencyRaw.support == u) checkEfficiency(v)
        if (s.hvacPower.support == u) checkHvacPower(v)
        if (s.targetTempC.support == u) checkTemp(v)
        if (s.fanSpeed.support == u) checkFan(v)
        if (s.acOn.support == u) checkAc(v)
        if (s.seatHeatDriver.support == u) checkSeatHeat(v)
        if (s.driverOccupied.support == u) checkOccupancy(v)
        if (s.gear.support == u) readGear(v, listen = true)
    }

    fun release() {
        val v = vehicle ?: return
        registered.forEach { runCatching(it) }
        registered.clear()
        runCatching { v.release() }
        vehicle = null
    }

    private fun checkCapabilities(v: Vehicle) {
        checkSoc(v)
        readCapacity(v)
        readRange(v, listen = true)
        checkEfficiency(v)
        checkHvacPower(v)
        checkTemp(v)
        checkFan(v)
        checkAc(v)
        checkSeatHeat(v)
        checkOccupancy(v)
        readGear(v, listen = true)
    }

    // ---- EV battery -------------------------------------------------------------------

    private fun checkSoc(v: Vehicle) = safe({ s, r -> s.copy(soc = r) }) {
        v.getEvBattery().checkEvBatteryStateOfChargeCapabilities({ config ->
            if (config.readable()) {
                readSoc(v)
                register {
                    val l = object : EvBatteryStateOfChargeListener {
                        override fun onEvBatteryStateOfChargeUpdated(soc: Int?) = set { copy(soc = Reading(soc, Support.SUPPORTED)) }
                        override fun onFailed(e: Exception) { Log.w(TAG, "SoC listener", e) }
                    }
                    v.getEvBattery().registerEvBatteryStateOfCharge(l)
                    registered += { v.getEvBattery().unregisterEvBatteryStateOfCharge(l) }
                }
            } else unsupported { copy(soc = it) }
        }, { unsupported { copy(soc = it) } })
    }

    private fun readSoc(v: Vehicle) = safe({ s, r -> s.copy(soc = r) }) {
        v.getEvBattery().getEvBatteryStateOfCharge(
            { value -> set { copy(soc = Reading(value, Support.SUPPORTED)) } },
            { unsupported { copy(soc = it) } }
        )
    }

    private fun readCapacity(v: Vehicle) = safe({ s, r -> s.copy(capacityRaw = r) }) {
        v.getEvBattery().getEvBatteryCapacity(
            { value -> set { copy(capacityRaw = Reading(value, Support.SUPPORTED)) } },
            { unsupported { copy(capacityRaw = it) } }
        )
    }

    private fun checkEfficiency(v: Vehicle) = safe({ s, r -> s.copy(efficiencyRaw = r) }) {
        v.getEvBattery().checkCurrentEvEfficiencyCapabilities({ config ->
            if (config.readable()) {
                readEfficiency(v)
                register {
                    val l = object : EvBatteryEfficiencyListener {
                        override fun onEvBatteryEfficiencyUpdated(value: Float?) = set { copy(efficiencyRaw = Reading(value, Support.SUPPORTED)) }
                        override fun onFailed(e: Exception) { Log.w(TAG, "efficiency listener", e) }
                    }
                    v.getEvBattery().registerCurrentEvEfficiency(l)
                    registered += { v.getEvBattery().unregisterCurrentEvEfficiency(l) }
                }
            } else unsupported { copy(efficiencyRaw = it) }
        }, { unsupported { copy(efficiencyRaw = it) } })
    }

    private fun readEfficiency(v: Vehicle) = safe({ s, r -> s.copy(efficiencyRaw = r) }) {
        v.getEvBattery().getCurrentEvEfficiency(
            { value -> set { copy(efficiencyRaw = Reading(value, Support.SUPPORTED)) } },
            { unsupported { copy(efficiencyRaw = it) } }
        )
    }

    private fun readRange(v: Vehicle, listen: Boolean = false) = safe({ s, r -> s.copy(rangeRaw = r) }) {
        v.getOdometer().getRangeRemainingDistance(
            { value ->
                set { copy(rangeRaw = Reading(value, Support.SUPPORTED)) }
                if (listen) register {
                    val l = object : RangeRemainingListener {
                        override fun onRangeRemainingChanged(value: Float?) = set { copy(rangeRaw = Reading(value, Support.SUPPORTED)) }
                        override fun onFailed(e: Exception) { Log.w(TAG, "range listener", e) }
                    }
                    v.getOdometer().registerRangeRemainingListener(l)
                    registered += { v.getOdometer().unregisterRangeRemainingListener(l) }
                }
            },
            { unsupported { copy(rangeRaw = it) } }
        )
    }

    // ---- HVAC -------------------------------------------------------------------------

    private fun checkHvacPower(v: Vehicle) = safe({ s, r -> s.copy(hvacPower = r) }) {
        v.getHVAC().checkHvacPowerCapabilities({ config: CapabilityConfig, areas: List<SeatArea> ->
            val area = pickDriverArea(areas)
            if (config.readable() && area != null) {
                hvacPowerArea = area
                readHvacPower(v, area)
                register {
                    val l = object : HvacPowerStateListener {
                        override fun onHvacPowerStateUpdated(on: Boolean?) = set { copy(hvacPower = Reading(on, Support.SUPPORTED)) }
                        override fun onFailed(e: Exception) { Log.w(TAG, "hvac power listener", e) }
                    }
                    v.getHVAC().registerHvacPowerState(area, l)
                    registered += { v.getHVAC().unregisterHvacPowerState(area, l) }
                }
            } else unsupported { copy(hvacPower = it) }
        }, { unsupported { copy(hvacPower = it) } })
    }

    private fun readHvacPower(v: Vehicle, area: SeatArea) = safe({ s, r -> s.copy(hvacPower = r) }) {
        v.getHVAC().getHvacPowerState(area,
            { on -> set { copy(hvacPower = Reading(on, Support.SUPPORTED)) } },
            { unsupported { copy(hvacPower = it) } })
    }

    private fun checkTemp(v: Vehicle) = safe({ s, r -> s.copy(targetTempC = r) }) {
        v.getHVAC().checkHvacTemperatureCapabilities({ config: CapabilityConfig, bounds: Map<SeatArea, *> ->
            val area = pickDriverArea(bounds.keys.toList())
            if (config.readable() && area != null) {
                hvacTempArea = area
                readTemp(v, area)
                register {
                    val l = object : HvacTemperatureListener {
                        override fun onHvacTemperatureUpdated(value: Float?) = set { copy(targetTempC = Reading(value, Support.SUPPORTED)) }
                        override fun onFailed(e: Exception) { Log.w(TAG, "temperature listener", e) }
                    }
                    v.getHVAC().registerHvacTemperature(area, l)
                    registered += { v.getHVAC().unregisterHvacTemperature(area, l) }
                }
            } else unsupported { copy(targetTempC = it) }
        }, { unsupported { copy(targetTempC = it) } })
    }

    private fun readTemp(v: Vehicle, area: SeatArea) = safe({ s, r -> s.copy(targetTempC = r) }) {
        v.getHVAC().getHvacTemperature(area, TemperatureUnit.CELSIUS,
            { value -> set { copy(targetTempC = Reading(value, Support.SUPPORTED)) } },
            { unsupported { copy(targetTempC = it) } })
    }

    private fun checkFan(v: Vehicle) = safe({ s, r -> s.copy(fanSpeed = r) }) {
        v.getHVAC().checkHvacFanSpeedCapabilities({ config: CapabilityConfig, bounds: Map<SeatArea, *> ->
            val area = pickDriverArea(bounds.keys.toList())
            if (config.readable() && area != null) {
                hvacFanArea = area
                readFan(v, area)
                register {
                    val l = object : HvacFanSpeedListener {
                        override fun onHvacFanSpeedUpdated(value: Int?) = set { copy(fanSpeed = Reading(value, Support.SUPPORTED)) }
                        override fun onFailed(e: Exception) { Log.w(TAG, "fan listener", e) }
                    }
                    v.getHVAC().registerHvacFanSpeed(area, l)
                    registered += { v.getHVAC().unregisterHvacFanSpeed(area, l) }
                }
            } else unsupported { copy(fanSpeed = it) }
        }, { unsupported { copy(fanSpeed = it) } })
    }

    private fun readFan(v: Vehicle, area: SeatArea) = safe({ s, r -> s.copy(fanSpeed = r) }) {
        v.getHVAC().getHvacFanSpeed(area,
            { value -> set { copy(fanSpeed = Reading(value, Support.SUPPORTED)) } },
            { unsupported { copy(fanSpeed = it) } })
    }

    private fun checkAc(v: Vehicle) = safe({ s, r -> s.copy(acOn = r) }) {
        v.getHVAC().checkHvacAcCapabilities({ config: CapabilityConfig, areas: List<SeatArea> ->
            val area = pickDriverArea(areas)
            if (config.readable() && area != null) {
                hvacAcArea = area
                readAc(v, area)
                register {
                    val l = object : HvacAcStateListener {
                        override fun onHvacAcStateUpdated(on: Boolean?) = set { copy(acOn = Reading(on, Support.SUPPORTED)) }
                        override fun onFailed(e: Exception) { Log.w(TAG, "ac listener", e) }
                    }
                    v.getHVAC().registerHvacAcState(area, l)
                    registered += { v.getHVAC().unregisterHvacAcState(area, l) }
                }
            } else unsupported { copy(acOn = it) }
        }, { unsupported { copy(acOn = it) } })
    }

    private fun readAc(v: Vehicle, area: SeatArea) = safe({ s, r -> s.copy(acOn = r) }) {
        v.getHVAC().getHvacAcState(area,
            { on -> set { copy(acOn = Reading(on, Support.SUPPORTED)) } },
            { unsupported { copy(acOn = it) } })
    }

    // ---- Seats ------------------------------------------------------------------------

    private fun checkSeatHeat(v: Vehicle) = safe({ s, r -> s.copy(seatHeatDriver = r, seatHeatPassenger = r) }) {
        v.getSeat().checkSeatTemperatureCapabilities({ config: CapabilityConfig, bounds: Map<SeatArea, *> ->
            val areas = bounds.keys.toList()
            val driver = areas.firstOrNull { SeatValue.ROW_1_LEFT in it.values }
            val passenger = areas.firstOrNull { SeatValue.ROW_1_RIGHT in it.values && it != driver }
            if (!config.readable() || driver == null) {
                unsupported { copy(seatHeatDriver = it, seatHeatPassenger = it) }
                return@checkSeatTemperatureCapabilities
            }
            seatHeatDriverArea = driver
            watchSeatHeat(v, driver) { r -> copy(seatHeatDriver = r) }
            if (passenger != null) {
                seatHeatPassengerArea = passenger
                watchSeatHeat(v, passenger) { r -> copy(seatHeatPassenger = r) }
            } else unsupported { copy(seatHeatPassenger = it) }
        }, { unsupported { copy(seatHeatDriver = it, seatHeatPassenger = it) } })
    }

    private fun watchSeatHeat(v: Vehicle, area: SeatArea, put: VehicleSnapshot.(Reading<Int>) -> VehicleSnapshot) {
        readSeatHeat(v, area, put)
        register {
            val l = object : HvacSeatTemperatureListener {
                override fun onHvacSeatTemperatureUpdated(value: Int?) = set { put(Reading(value, Support.SUPPORTED)) }
                override fun onFailed(e: Exception) { Log.w(TAG, "seat heat listener", e) }
            }
            v.getSeat().registerHvacSeatTemperature(area, l)
            registered += { v.getSeat().unregisterHvacSeatTemperature(area, l) }
        }
    }

    private fun readSeatHeat(v: Vehicle, area: SeatArea, put: VehicleSnapshot.(Reading<Int>) -> VehicleSnapshot) =
        safe({ s, r -> s.put(r) }) {
            v.getSeat().getHvacSeatTemperature(area,
                { value -> set { put(Reading(value, Support.SUPPORTED)) } },
                { unsupported(put) })
        }

    private fun checkOccupancy(v: Vehicle) = safe({ s, r -> s.copy(driverOccupied = r) }) {
        v.getSeat().checkSeatOccupancyCapabilities({ config: CapabilityConfig, areas: List<SeatArea> ->
            val area = areas.firstOrNull { SeatValue.ROW_1_LEFT in it.values }
            if (config.readable() && area != null) {
                occupancyArea = area
                readOccupancy(v, area)
                register {
                    val l = object : SeatOccupancyListener {
                        override fun onSeatOccupancyUpdated(value: Boolean?) = set { copy(driverOccupied = Reading(value, Support.SUPPORTED)) }
                        override fun onFailed(e: Exception) { Log.w(TAG, "occupancy listener", e) }
                    }
                    v.getSeat().registerSeatOccupancy(area, l)
                    registered += { v.getSeat().unregisterSeatOccupancy(area, l) }
                }
            } else unsupported { copy(driverOccupied = it) }
        }, { unsupported { copy(driverOccupied = it) } })
    }

    private fun readOccupancy(v: Vehicle, area: SeatArea) = safe({ s, r -> s.copy(driverOccupied = r) }) {
        v.getSeat().getCurrentSeatOccupancy(area,
            { value -> set { copy(driverOccupied = Reading(value, Support.SUPPORTED)) } },
            { unsupported { copy(driverOccupied = it) } })
    }

    // ---- Gear (SDK; AAOS CarPropertyManager is the fallback in CarSignals) ------------

    private fun readGear(v: Vehicle, listen: Boolean = false) = safe({ s, r -> s.copy(gear = r) }) {
        v.getGear().getCurrentGearSelectionState(
            { g ->
                set { copy(gear = Reading(g?.toGearState(), Support.SUPPORTED)) }
                if (listen) register {
                    val l = object : GearSelectionListener {
                        override fun onSelected(gear: VehicleGear?) = set { copy(gear = Reading(gear?.toGearState(), Support.SUPPORTED)) }
                        override fun onFailed(e: Exception) { Log.w(TAG, "gear listener", e) }
                    }
                    v.getGear().registerGearSelection(l)
                    registered += { v.getGear().unregisterGearSelection(l) }
                }
            },
            { unsupported { copy(gear = it) } }
        )
    }

    // ---- helpers ----------------------------------------------------------------------

    private fun set(transform: VehicleSnapshot.() -> VehicleSnapshot) = _state.update { it.transform() }

    private fun <T> unsupported(put: VehicleSnapshot.(Reading<T>) -> VehicleSnapshot) =
        set { put(Reading(null, Support.UNSUPPORTED)) }

    private inline fun safe(
        crossinline markUnsupported: (VehicleSnapshot, Reading<Nothing>) -> VehicleSnapshot,
        block: () -> Unit
    ) {
        try {
            block()
        } catch (e: Exception) {
            Log.w(TAG, "vehicle call failed", e)
            _state.update { markUnsupported(it, Reading(null, Support.UNSUPPORTED)) }
        }
    }

    private inline fun register(block: () -> Unit) {
        try {
            block()
        } catch (e: Exception) {
            // Reads still work through polling; only log.
            Log.w(TAG, "listener registration failed", e)
        }
    }

    private fun CapabilityConfig.readable(): Boolean =
        accessType == AccessType.READ || accessType == AccessType.READ_WRITE

    private fun pickDriverArea(areas: List<SeatArea>): SeatArea? =
        areas.firstOrNull { SeatValue.ROW_1_LEFT in it.values } ?: areas.firstOrNull()

    private fun VehicleGear.toGearState(): GearState = when (this) {
        VehicleGear.GEAR_PARK -> GearState.PARK
        VehicleGear.GEAR_REVERSE -> GearState.REVERSE
        VehicleGear.GEAR_NEUTRAL -> GearState.NEUTRAL
        VehicleGear.GEAR_UNKNOWN -> GearState.UNKNOWN
        else -> GearState.DRIVE
    }

    private companion object {
        const val TAG = "EvStayVehicle"
    }
}
