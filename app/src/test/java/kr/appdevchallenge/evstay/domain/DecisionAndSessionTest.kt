package kr.appdevchallenge.evstay.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DecisionAndSessionTest {

    private val temp = SettingComparison("온도 2도 낮추면", deltaMinutes = 25, lowDeltaMinutes = 20)
    private val seat = SettingComparison("공조 끄고 운전석 열선 2단만(조수석 끔) 쓰면", deltaMinutes = 300, lowDeltaMinutes = 240)

    @Test
    fun advise_noTargetAndReached() {
        assertEquals(SettingAdvice.NoTarget, StayDecision.advise(null, listOf(temp)))
        assertEquals(SettingAdvice.Reached, StayDecision.advise(TargetCheck(0.0, met = true, marginMinutes = 0, reached = true), listOf(temp)))
    }

    @Test
    fun advise_alreadyMet() {
        assertEquals(SettingAdvice.AlreadyMet(15), StayDecision.advise(TargetCheck(3.0, met = true, marginMinutes = 15), listOf(temp)))
    }

    @Test
    fun advise_listsOnlySettingsThatCoverTheGapOnTheShortEnd() {
        // 34 min short: temp (+20 on the short end) is not enough even though its middle gain is 25.
        val r = StayDecision.advise(TargetCheck(4.0, met = false, marginMinutes = -34), listOf(temp, seat))
        assertEquals(SettingAdvice.CoveredBy(34, listOf(seat)), r)
        val small = StayDecision.advise(TargetCheck(4.0, met = false, marginMinutes = -20), listOf(temp, seat))
        assertEquals(SettingAdvice.CoveredBy(20, listOf(temp, seat)), small)
    }

    @Test
    fun advise_notCoveredGivesBestGainOrNone() {
        val r = StayDecision.advise(TargetCheck(9.0, met = false, marginMinutes = -400), listOf(temp, seat))
        assertEquals(SettingAdvice.NotCovered(400, seat), r)
        assertEquals(SettingAdvice.NotCovered(60, null), StayDecision.advise(TargetCheck(1.0, met = false, marginMinutes = -60), emptyList()))
    }

    @Test
    fun session_activeOnlyWhenServiceReportedRunningAndNotificationsAllowed() {
        assertFalse(SessionStatus.describe(MonitorState.STARTING, null, true).active)
        assertFalse(SessionStatus.describe(MonitorState.OFF, null, true).active)
        val running = SessionStatus.describe(MonitorState.RUNNING, null, true)
        assertTrue(running.active)
        assertEquals("정차 감시 중", running.headline)
        val noPermission = SessionStatus.describe(MonitorState.RUNNING, null, false)
        assertFalse(noPermission.active)
        assertFalse(noPermission.headline == "정차 감시 중")
        assertTrue(noPermission.detail!!.contains("알림 권한"))
    }

    @Test
    fun session_failureIsVisibleWithReason() {
        val v = SessionStatus.describe(MonitorState.FAILED, "서비스 시작이 거부됨(X)", true)
        assertFalse(v.active)
        assertTrue(v.headline.contains("시작하지 못함"))
        assertTrue(v.detail!!.contains("서비스 시작이 거부됨"))
    }

    @Test
    fun session_startTimesOutOnlyWhileStarting() {
        assertFalse(SessionStatus.startTimedOut(MonitorState.STARTING, 0, SessionStatus.START_TIMEOUT_MS))
        assertTrue(SessionStatus.startTimedOut(MonitorState.STARTING, 0, SessionStatus.START_TIMEOUT_MS + 1))
        assertFalse(SessionStatus.startTimedOut(MonitorState.RUNNING, 0, 60_000))
    }

    @Test
    fun advise_gainJustBelowShortfall_isNotCoveredEvenIfRoundedValueReachesIt() {
        // Short 34.15 min; the alternative gains 33.9 min, shown rounded to 35.
        val target = TargetCheck(4.0, met = false, marginMinutes = -34, marginExactMinutes = -34.15)
        val almost = SettingComparison("온도 2도 낮추면", deltaMinutes = 40, lowDeltaMinutes = 35, lowDeltaExactMinutes = 33.9)
        val r = StayDecision.advise(target, listOf(almost))
        assertEquals(SettingAdvice.NotCovered(34, almost), r)
        val enough = almost.copy(lowDeltaExactMinutes = 34.2)
        assertEquals(SettingAdvice.CoveredBy(34, listOf(enough)), StayDecision.advise(target, listOf(enough)))
    }

    private fun plan(
        state: MonitorState = MonitorState.RUNNING, session: Boolean = true, driving: Boolean = false,
        park: Boolean = true, foreground: Boolean = false, reached: Boolean = false, notified: Boolean = false
    ) = NotificationGate.plan(state, session, driving, park, foreground, reached, notified)

    @Test
    fun gate_notifiesOnlyWhileRunningSessionInConfirmedPark() {
        assertTrue(plan().mayWarn)
        assertTrue(plan(reached = true).notifyTargetReached)
        // No session, or the service not (yet) running: nothing is posted.
        assertFalse(plan(session = false, state = MonitorState.OFF).mayWarn)
        assertFalse(plan(session = false, state = MonitorState.OFF, reached = true).notifyTargetReached)
        assertFalse(plan(state = MonitorState.STARTING).mayWarn)
        assertFalse(plan(state = MonitorState.FAILED, session = false, reached = true).notifyTargetReached)
        // Gear not confirmed P (unknown gear): nothing is posted.
        assertFalse(plan(park = false).mayWarn)
        assertFalse(plan(park = false, reached = true).notifyTargetReached)
        // App on screen: no warning notification; already notified: no repeat.
        assertFalse(plan(foreground = true).mayWarn)
        assertFalse(plan(reached = true, notified = true).notifyTargetReached)
    }

    @Test
    fun gate_leavingParkEndsSessionFirstAndCancels() {
        val p = plan(driving = true, park = false, reached = true)
        assertTrue(p.endSession)
        assertTrue(p.cancelPosted)
        assertFalse(p.mayWarn)
        assertFalse(p.notifyTargetReached)
        // Driving without a session: nothing to end, nothing posted.
        val idle = plan(session = false, state = MonitorState.OFF, driving = true, park = false)
        assertFalse(idle.endSession)
        assertFalse(idle.mayWarn)
    }

    @Test
    fun advise_prefersAlternativesThatKeepHeatingOn() {
        val target = TargetCheck(4.0, met = false, marginMinutes = -30, marginExactMinutes = -30.0)
        val off = SettingComparison("공조 끄고 운전석 열선 2단만(조수석 끔) 쓰면", 300, 240, 240.0, keepsHvacOn = false)
        val on = SettingComparison("온도 2도 낮추고 풍량 6단→4단", 40, 35, 35.0, keepsHvacOn = true)
        val covered = StayDecision.advise(target, listOf(off, on)) as SettingAdvice.CoveredBy
        assertEquals(listOf(on, off), covered.options)
        // Not covered: the best heating-on alternative is shown even if turning HVAC off gains more.
        val far = TargetCheck(9.0, met = false, marginMinutes = -400, marginExactMinutes = -400.0)
        assertEquals(SettingAdvice.NotCovered(400, on), StayDecision.advise(far, listOf(off, on)))
    }

    @Test
    fun restore_savedSessionIsStartedAgainOnlyWhileStillValid() {
        val now = 1_000_000L
        fun d(state: MonitorState = MonitorState.STARTING, park: Boolean = true, known: Boolean = true,
              target: Long? = null, waited: Long = 0) = SessionRestore.decide(state, park, known, target, now, waited)
        // Force-stopped app relaunched in P with no planned time / planned time ahead: start the service again.
        assertEquals(RestoreAction.RESUME, d())
        assertEquals(RestoreAction.RESUME, d(target = now + 60_000))
        // Sticky restart already reported in.
        assertEquals(RestoreAction.ALREADY_RUNNING, d(state = MonitorState.RUNNING))
        // Planned time passed, or gear known and not P: the stay is over.
        assertEquals(RestoreAction.CLEAR, d(target = now))
        assertEquals(RestoreAction.CLEAR, d(park = false, known = true))
        // Gear not read yet: wait, then give up after the wait.
        assertEquals(RestoreAction.WAIT, d(park = false, known = false, waited = SessionRestore.GEAR_WAIT_MS))
        assertEquals(RestoreAction.CLEAR, d(park = false, known = false, waited = SessionRestore.GEAR_WAIT_MS + 1))
    }
}
