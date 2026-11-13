package kr.appdevchallenge.evstay

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kr.appdevchallenge.evstay.ui.EvStayTheme
import kr.appdevchallenge.evstay.ui.StayActions
import kr.appdevchallenge.evstay.ui.StayScreen

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private val notificationPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val actions = StayActions(
            onDemo = viewModel::setDemo,
            onMargin = viewModel::changeMargin,
            onManualKm = viewModel::changeManualHomeKm,
            onClearManualKm = viewModel::clearManualHomeKm,
            onPickHome = viewModel::pickHome,
            onOutsideTemp = viewModel::changeOutsideTemp,
            onMinCabinTemp = viewModel::changeMinCabinTemp,
            onClearMinCabinTemp = viewModel::clearMinCabinTemp,
            onStayConditions = viewModel::setStayConditions,
            onTarget = viewModel::changeTarget,
            onClearTarget = viewModel::clearTarget,
            onStartSession = viewModel::startSession,
            onStopSession = viewModel::stopSession,
            onRequestNotifications = ::requestNotificationPermission
        )
        setContent {
            val ui = viewModel.ui.collectAsStateWithLifecycle().value
            EvStayTheme { StayScreen(ui, actions) }
        }
        requestNotificationPermission()
    }

    /** Asks for POST_NOTIFICATIONS when missing; the screen reads the result from StayMonitor. */
    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            runCatching { notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS) }
        }
    }

    override fun onStart() {
        super.onStart()
        viewModel.setForeground(true)
    }

    override fun onStop() {
        viewModel.setForeground(false)
        super.onStop()
    }
}
