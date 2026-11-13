package kr.appdevchallenge.evstay.domain

/** State of the stay-monitoring foreground service, as reported by the service itself. */
enum class MonitorState { OFF, STARTING, RUNNING, FAILED }

/**
 * What the screen may claim about monitoring. "감시 중" is shown only after the service reported
 * that startForeground succeeded, and off-screen warnings are promised only when notifications
 * can actually be posted.
 */
data class SessionView(
    val headline: String,
    /** True only for a running service that can post notifications. */
    val active: Boolean,
    val detail: String?
)

object SessionStatus {
    /** A start request with no answer from the service within this time is reported as failed. */
    const val START_TIMEOUT_MS = 10_000L

    fun startTimedOut(state: MonitorState, startedAtMs: Long, nowMs: Long): Boolean =
        state == MonitorState.STARTING && nowMs - startedAtMs > START_TIMEOUT_MS

    fun describe(state: MonitorState, error: String?, notificationsAllowed: Boolean): SessionView {
        val noPermission = "알림 권한이 꺼져 있어 앱 밖에서는 경고를 보낼 수 없습니다"
        return when (state) {
            MonitorState.OFF -> SessionView(
                "정차 감시 꺼짐", false,
                if (notificationsAllowed) "앱 화면이 보일 때만 경고합니다" else noPermission
            )
            MonitorState.STARTING -> SessionView("정차 감시 시작 중…", false, if (notificationsAllowed) null else noPermission)
            MonitorState.RUNNING -> if (notificationsAllowed) {
                SessionView("정차 감시 중", true, "앱을 나가도 알림으로 경고합니다(Pleos 화면 꺼짐 동작은 미확인)")
            } else {
                SessionView("감시 서비스 실행 중 · 알림 권한 없음", false, noPermission)
            }
            MonitorState.FAILED -> SessionView(
                "정차 감시를 시작하지 못함", false,
                (error ?: "원인 모름") + " — 앱 화면이 보일 때만 경고합니다"
            )
        }
    }
}
