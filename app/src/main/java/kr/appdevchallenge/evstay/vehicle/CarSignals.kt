package kr.appdevchallenge.evstay.vehicle

import android.car.Car
import android.car.VehicleGear
import android.car.VehiclePropertyIds
import android.car.drivingstate.CarUxRestrictionsManager
import android.car.hardware.CarPropertyValue
import android.car.hardware.property.CarPropertyManager
import android.content.Context
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

data class CarState(
    /** null = UX restrictions service unavailable. */
    val distractionOptimizationRequired: Boolean? = null,
    /** AAOS GEAR_SELECTION, used when the Vehicle SDK gear is not available. */
    val gear: Reading<GearState> = Reading()
)

/**
 * AAOS car services: driving UX restrictions and GEAR_SELECTION. Any failure (no car library,
 * missing permission, property absent) leaves the value unknown instead of crashing.
 */
class CarSignals(context: Context) {

    private val appContext = context.applicationContext
    private var car: Car? = null
    private var uxManager: CarUxRestrictionsManager? = null
    private var propertyManager: CarPropertyManager? = null
    private val _state = MutableStateFlow(CarState())
    val state: StateFlow<CarState> = _state.asStateFlow()

    private val gearCallback = object : CarPropertyManager.CarPropertyEventCallback {
        override fun onChangeEvent(value: CarPropertyValue<*>) {
            if (value.propertyId == VehiclePropertyIds.GEAR_SELECTION) {
                val gear = (value.value as? Int)?.toGearState()
                _state.update { it.copy(gear = Reading(gear, Support.SUPPORTED)) }
            }
        }

        override fun onErrorEvent(propertyId: Int, zone: Int) {
            Log.w(TAG, "gear property error $propertyId")
        }
    }

    fun start() {
        if (car != null) return
        val c = try {
            Car.createCar(appContext)
        } catch (t: Throwable) {
            Log.w(TAG, "Car service unavailable", t)
            null
        }
        if (c == null) {
            _state.update { it.copy(gear = Reading(null, Support.UNSUPPORTED)) }
            return
        }
        car = c
        startUxRestrictions(c)
        startGear(c)
    }

    private fun startUxRestrictions(c: Car) {
        try {
            val manager = c.getCarManager(Car.CAR_UX_RESTRICTION_SERVICE) as? CarUxRestrictionsManager ?: return
            uxManager = manager
            val current = manager.currentCarUxRestrictions
            _state.update { it.copy(distractionOptimizationRequired = current?.isRequiresDistractionOptimization) }
            manager.registerListener { restrictions ->
                _state.update { it.copy(distractionOptimizationRequired = restrictions.isRequiresDistractionOptimization) }
            }
        } catch (t: Throwable) {
            Log.w(TAG, "UX restrictions unavailable", t)
        }
    }

    private fun startGear(c: Car) {
        try {
            val manager = c.getCarManager(Car.PROPERTY_SERVICE) as? CarPropertyManager
            if (manager == null || manager.getCarPropertyConfig(VehiclePropertyIds.GEAR_SELECTION) == null) {
                _state.update { it.copy(gear = Reading(null, Support.UNSUPPORTED)) }
                return
            }
            propertyManager = manager
            val initial = runCatching { manager.getIntProperty(VehiclePropertyIds.GEAR_SELECTION, 0) }.getOrNull()
            _state.update { it.copy(gear = Reading(initial?.toGearState(), Support.SUPPORTED)) }
            manager.registerCallback(gearCallback, VehiclePropertyIds.GEAR_SELECTION, CarPropertyManager.SENSOR_RATE_ONCHANGE)
        } catch (t: Throwable) {
            Log.w(TAG, "GEAR_SELECTION unavailable", t)
            _state.update { it.copy(gear = Reading(null, Support.UNSUPPORTED)) }
        }
    }

    fun release() {
        runCatching { uxManager?.unregisterListener() }
        runCatching { propertyManager?.unregisterCallback(gearCallback) }
        runCatching { car?.disconnect() }
        uxManager = null
        propertyManager = null
        car = null
    }

    private fun Int.toGearState(): GearState = when (this) {
        VehicleGear.GEAR_PARK -> GearState.PARK
        VehicleGear.GEAR_REVERSE -> GearState.REVERSE
        VehicleGear.GEAR_NEUTRAL -> GearState.NEUTRAL
        VehicleGear.GEAR_UNKNOWN -> GearState.UNKNOWN
        else -> GearState.DRIVE
    }

    private companion object {
        const val TAG = "EvStayCar"
    }
}
