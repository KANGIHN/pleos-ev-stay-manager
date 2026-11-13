package kr.appdevchallenge.evstay.domain

/**
 * What one evaluation may do about notifications. Session end is decided first: when the car
 * leaves P during a session, the session ends and posted notifications are cancelled in the same
 * step, and nothing new is posted. Otherwise notifications are allowed only while the monitoring
 * service is RUNNING and P is confirmed.
 */
data class NotificationPlan(
    val endSession: Boolean,
    /** Cancel posted warning / target notifications (session ended). */
    val cancelPosted: Boolean,
    val mayWarn: Boolean,
    val notifyTargetReached: Boolean
)

object NotificationGate {

    fun plan(
        monitorState: MonitorState,
        sessionActive: Boolean,
        driving: Boolean,
        parkConfirmed: Boolean,
        appInForeground: Boolean,
        targetReached: Boolean,
        targetAlreadyNotified: Boolean
    ): NotificationPlan {
        val endSession = sessionActive && driving
        val monitoring = !endSession && sessionActive && monitorState == MonitorState.RUNNING && parkConfirmed && !driving
        return NotificationPlan(
            endSession = endSession,
            cancelPosted = endSession,
            mayWarn = monitoring && !appInForeground,
            notifyTargetReached = monitoring && targetReached && !targetAlreadyNotified
        )
    }
}
