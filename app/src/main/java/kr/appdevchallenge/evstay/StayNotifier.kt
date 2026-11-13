package kr.appdevchallenge.evstay

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import kr.appdevchallenge.evstay.domain.StayEstimate
import kr.appdevchallenge.evstay.domain.StayStatus

/**
 * Posts a standard Android notification when the stay estimate crosses into a warning state
 * while the app is not in the foreground. One notification per new warning state.
 * Not verified on Pleos Connect: the platform may restrict third-party notifications.
 */
class StayNotifier(context: Context) {

    private val appContext = context.applicationContext
    private val manager = appContext.getSystemService(NotificationManager::class.java)
    private var lastKey: String? = null

    init {
        runCatching {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager?.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "귀가 예비선 경고", NotificationManager.IMPORTANCE_HIGH)
                )
            }
        }.onFailure { Log.w(TAG, "channel creation failed", it) }
    }

    fun maybeNotify(e: StayEstimate) {
        val key = when {
            e.status == StayStatus.HOME_UNREACHABLE -> "unreachable"
            e.status == StayStatus.BELOW_RESERVE -> "below"
            e.status == StayStatus.WARNING -> "warning"
            e.target?.let { !it.met && !it.reached } == true -> "target"
            else -> null
        }
        if (key == null) {
            lastKey = null
            return
        }
        if (key == lastKey) return
        val text = e.warning ?: e.target?.let { "현재 가정상 머물 예정 시각까지 약 ${-it.marginMinutes}분 부족합니다." } ?: return
        if (!canPost()) return
        post(text)
        lastKey = key
    }

    /** Removes posted warning / target notifications (session ended). */
    fun cancelAll() {
        lastKey = null
        runCatching { manager?.cancel(NOTIFICATION_ID) }.onFailure { Log.w(TAG, "cancel failed", it) }
    }

    fun notifyTargetReached() {
        if (!canPost()) return
        post("머물 예정 시각에 도달했습니다. 대기를 마치고 출발할 시간입니다.")
    }

    private fun post(text: String) {
        runCatching {
            val open = PendingIntent.getActivity(
                appContext, 0,
                Intent(appContext, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
            )
            val n = NotificationCompat.Builder(appContext, CHANNEL_ID)
                .setSmallIcon(R.mipmap.ic_launcher)
                .setContentTitle("정차 대기 배터리 매니저")
                .setContentText(text)
                .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setContentIntent(open)
                .setAutoCancel(true)
                .build()
            manager?.notify(NOTIFICATION_ID, n)
        }.onFailure { Log.w(TAG, "notify failed", it) }
    }

    /** Permission granted (API 33+) and notifications not blocked for the app. */
    fun canPost(): Boolean {
        val permitted = Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
            ContextCompat.checkSelfPermission(appContext, Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED
        return permitted && runCatching { NotificationManagerCompat.from(appContext).areNotificationsEnabled() }.getOrDefault(true)
    }

    private companion object {
        const val CHANNEL_ID = "reserve_warning"
        const val NOTIFICATION_ID = 1
        const val TAG = "EvStayNotify"
    }
}
