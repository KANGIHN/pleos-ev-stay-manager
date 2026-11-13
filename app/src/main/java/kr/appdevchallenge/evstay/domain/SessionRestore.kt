package kr.appdevchallenge.evstay.domain

/**
 * A session saved before the app was stopped (e.g. force-stop) is not running any more: the
 * foreground service is not restarted by the system. On relaunch it is either started again
 * or cleared — never left showing "시작 중" with no service behind it.
 */
enum class RestoreAction {
    /** Gear not read yet: decide on a later evaluation. */
    WAIT,
    /** Still a valid stay (P confirmed, planned time not passed): start the service again. */
    RESUME,
    /** The service already reported in (sticky restart): nothing to do. */
    ALREADY_RUNNING,
    /** No longer a valid stay, or P could not be confirmed in time: end the saved session. */
    CLEAR
}

object SessionRestore {
    /** How long to wait for a gear reading before giving up on the saved session. */
    const val GEAR_WAIT_MS = 10_000L

    fun decide(
        monitorState: MonitorState,
        parkConfirmed: Boolean,
        gearKnown: Boolean,
        targetEndMs: Long?,
        nowMs: Long,
        waitedMs: Long
    ): RestoreAction {
        if (monitorState == MonitorState.RUNNING) return RestoreAction.ALREADY_RUNNING
        if (targetEndMs != null && targetEndMs <= nowMs) return RestoreAction.CLEAR
        if (parkConfirmed) return RestoreAction.RESUME
        if (gearKnown) return RestoreAction.CLEAR
        return if (waitedMs > GEAR_WAIT_MS) RestoreAction.CLEAR else RestoreAction.WAIT
    }
}
