package kr.appdevchallenge.evstay

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kr.appdevchallenge.evstay.domain.StayStatus
import java.util.Locale
import kotlin.math.roundToInt

/**
 * Foreground service (type specialUse) that keeps the process — and so [StayMonitor]'s loop —
 * alive while a stay session is active, with a persistent "정차 감시 중" notification.
 * Behavior on Pleos when the screen turns off or the process is reclaimed is not verified.
 */
class StayMonitorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private var collectJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            runCatching {
                manager?.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "정차 감시 중", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val monitor = StayMonitor.get(this)
        if (!monitor.ui.value.sessionActive) {
            stopSelf()
            return START_NOT_STICKY
        }
        try {
            val n = build("정차 감시를 시작합니다")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                startForeground(NOTIFICATION_ID, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE)
            } else {
                startForeground(NOTIFICATION_ID, n)
            }
        } catch (e: Exception) {
            Log.w(TAG, "startForeground failed", e)
            monitor.onServiceFailed("상시 알림 서비스로 전환하지 못함(${e.javaClass.simpleName})")
            stopSelf()
            return START_NOT_STICKY
        }
        monitor.onServiceStarted()
        collectJob?.cancel()
        collectJob = scope.launch {
            monitor.ui.map { summary(it) }.distinctUntilChanged().collect { text ->
                runCatching { getSystemService(NotificationManager::class.java)?.notify(NOTIFICATION_ID, build(text)) }
                if (!monitor.ui.value.sessionActive) stopSelf()
            }
        }
        return START_STICKY
    }

    override fun onDestroy() {
        scope.cancel()
        StayMonitor.get(this).onServiceStopped()
        super.onDestroy()
    }

    private fun summary(ui: UiState): String {
        val e = ui.estimate ?: return "차량 정보를 읽는 중"
        val soc = e.socPercent?.roundToInt()?.let { "잔량 $it%" } ?: "잔량 모름"
        val main = when (e.status) {
            StayStatus.HOME_UNREACHABLE -> "현재 가정상 귀가 잔량 부족"
            StayStatus.BELOW_RESERVE -> "현재 가정상 귀가 예비선 아래"
            StayStatus.CHARGING -> "충전 중"
            StayStatus.NO_DATA -> "잔량을 읽는 중"
            else -> e.stayHours?.let { String.format(Locale.ROOT, "약 %.1f~%.1f시간 (추정)", it.low, it.high) } ?: "-"
        }
        return "$soc · $main"
    }

    private fun build(text: String): Notification {
        val open = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.mipmap.ic_launcher)
            .setContentTitle("정차 감시 중")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(open)
            .build()
    }

    private companion object {
        const val CHANNEL_ID = "stay_session"
        const val NOTIFICATION_ID = 2
        const val TAG = "EvStayService"
    }
}
