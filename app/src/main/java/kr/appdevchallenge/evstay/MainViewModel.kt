package kr.appdevchallenge.evstay

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import kotlinx.coroutines.flow.StateFlow
import kr.appdevchallenge.evstay.navi.HomePlace

/** Thin UI bridge to the process-wide [StayMonitor]. */
class MainViewModel(app: Application) : AndroidViewModel(app) {

    private val monitor = StayMonitor.get(app)
    val ui: StateFlow<UiState> = monitor.ui

    fun setDemo(enabled: Boolean) = monitor.setDemo(enabled)
    fun changeMargin(delta: Double) = monitor.changeMargin(delta)
    fun changeManualHomeKm(delta: Double) = monitor.changeManualHomeKm(delta)
    fun clearManualHomeKm() = monitor.clearManualHomeKm()
    fun pickHome(place: HomePlace) = monitor.pickHome(place)
    fun changeOutsideTemp(delta: Double) = monitor.changeOutsideTemp(delta)
    fun changeMinCabinTemp(delta: Double) = monitor.changeMinCabinTemp(delta)
    fun clearMinCabinTemp() = monitor.clearMinCabinTemp()
    fun setStayConditions(keepHvacOn: Boolean, minSetpointC: Double?, passengerPresent: Boolean) =
        monitor.setStayConditions(keepHvacOn, minSetpointC, passengerPresent)
    fun changeTarget(deltaMinutes: Int) = monitor.changeTarget(deltaMinutes)
    fun clearTarget() = monitor.clearTarget()
    fun startSession() = monitor.startSession()
    fun stopSession() = monitor.stopSession()
    fun setForeground(value: Boolean) = monitor.setForeground(value)
    fun nowAppMs(): Long = monitor.nowAppMs()
}
