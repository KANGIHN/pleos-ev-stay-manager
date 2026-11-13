package kr.appdevchallenge.evstay.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TripRequestTrackerTest {

    private val homeA = Coordinate(37.50, 127.00)
    private val homeB = Coordinate(37.51, 127.01) // ~1.4 km from A: similar route distance

    private fun accepted(r: TripRequestTracker.Result): TripRequestTracker.Result.Accepted =
        r as? TripRequestTracker.Result.Accepted ?: throw AssertionError("expected Accepted, got $r")

    @Test
    fun oneRequestInFlight_responseAttributedToIt() {
        val t = TripRequestTracker()
        assertTrue(t.tryStart("A", 35.0, 0))
        assertFalse(t.tryStart("B", 35.0, 1_000))
        val a = accepted(t.onResponse(2_000))
        assertEquals("A", a.placeKey)
        assertEquals(35.0, a.socAtRequest!!, 0.0)
        assertTrue(t.tryStart("B", 34.0, 3_000))
        val b = accepted(t.onResponse(4_000))
        assertEquals("B", b.placeKey)
        assertTrue(b.seq > a.seq)
    }

    @Test
    fun destinationChangeWhileInFlight_discardsTheOldAnswer() {
        val t = TripRequestTracker()
        t.tryStart("old-home", 35.0, 0)
        t.destinationChanged()
        val r = t.onResponse(1_000)
        assertTrue(r is TripRequestTracker.Result.Discarded)
        // The next request is for the new home and its answer is accepted.
        assertTrue(t.tryStart("new-home", 35.0, 2_000))
        assertEquals("new-home", accepted(t.onResponse(3_000)).placeKey)
    }

    @Test
    fun lateAnswerAfterTimeout_isDiscardedNotGivenToNextRequest() {
        val t = TripRequestTracker(timeoutMs = 30_000, lateAnswerWindowMs = 600_000)
        t.tryStart("A", 35.0, 0)
        // Timed out: no new request while the orphan may still answer.
        assertFalse(t.tryStart("B", 35.0, 40_000))
        val late = t.onResponse(50_000)
        assertTrue(late is TripRequestTracker.Result.Discarded)
        assertTrue(t.tryStart("B", 35.0, 51_000))
        assertEquals("B", accepted(t.onResponse(52_000)).placeKey)
    }

    @Test
    fun orphanThatNeverAnswers_blocksUntilLateWindowEnds() {
        val t = TripRequestTracker(timeoutMs = 30_000, lateAnswerWindowMs = 600_000)
        t.tryStart("A", 35.0, 0)
        // The old 2-minute grace no longer reopens the slot: at 160 s A could still answer.
        assertFalse(t.tryStart("B", 35.0, 160_000))
        assertFalse(t.tryStart("B", 35.0, 600_000))
        assertTrue(t.tryStart("B", 35.0, 600_001))
    }

    @Test
    fun reviewCase_answerOfA_at161s_isNotAcceptedAsB() {
        // Review case with the production defaults: A sent at 0, B tried at 160 s, A's answer at 161 s.
        val t = TripRequestTracker()
        assertTrue(t.tryStart("A", 35.0, 0, homeA))
        val sentB = t.tryStart("B", 35.0, 160_000, homeB)
        assertFalse(sentB)
        val r = t.onResponse(161_000)
        assertTrue(r is TripRequestTracker.Result.Discarded)
    }

    @Test
    fun destinationInAnswer_rejectsOldAnswerEvenAfterLateWindow() {
        val t = TripRequestTracker(timeoutMs = 30_000, lateAnswerWindowMs = 600_000)
        t.tryStart("A", 35.0, 0, homeA)
        assertTrue(t.tryStart("B", 35.0, 700_000, homeB))
        // A's answer arrives very late but names A's destination: not attributed to B.
        val stray = t.onResponse(701_000, destination = homeA)
        assertTrue(stray is TripRequestTracker.Result.Discarded)
        // B is still in flight and its own answer is accepted, verified by destination.
        val b = accepted(t.onResponse(702_000, destination = Coordinate(37.5102, 127.0101)))
        assertEquals("B", b.placeKey)
        assertTrue(b.verifiedByDestination)
    }

    @Test
    fun destinationInAnswer_matchingAnOrphan_isDiscarded() {
        val t = TripRequestTracker(timeoutMs = 30_000, lateAnswerWindowMs = 600_000)
        t.tryStart("A", 35.0, 0, homeA)
        val r = t.onResponse(45_000, destination = homeA)
        assertEquals(TripRequestTracker.Result.Discarded("시간 초과 뒤 늦게 도착한 응답(목적지 대조)"), r)
        assertTrue(t.tryStart("A", 35.0, 46_000, homeA))
    }

    @Test
    fun residualRisk_answerLaterThanWindow_withoutDestination_isAttributedToNewerRequest() {
        // Documents the remaining gap stated in plan.md/README: no destination in the answer and
        // an answer later than the window. Only TripDistance's straight-line check guards this.
        val t = TripRequestTracker(timeoutMs = 30_000, lateAnswerWindowMs = 600_000)
        t.tryStart("A", 35.0, 0, homeA)
        assertTrue(t.tryStart("B", 35.0, 600_001, homeB))
        val r = accepted(t.onResponse(600_500))
        assertEquals("B", r.placeKey)
        assertFalse(r.verifiedByDestination)
    }

    @Test
    fun unsolicitedAnswer_isDiscarded() {
        assertTrue(TripRequestTracker().onResponse(0) is TripRequestTracker.Result.Discarded)
    }

    @Test
    fun failure_freesTheSlot() {
        val t = TripRequestTracker()
        t.tryStart("A", 35.0, 0)
        t.onFailure()
        assertTrue(t.tryStart("A", 35.0, 1_000))
    }

    @Test
    fun tripDistance_checkedAgainstStraightLine() {
        assertEquals(TripDistance.Reading.Km(12.0, checked = true), TripDistance.interpret(12_000, 9.0))
        // 12 (km?) read as 0.012 km cannot be a route to a home 9 km away.
        assertTrue(TripDistance.interpret(12, 9.0) is TripDistance.Reading.Rejected)
        assertEquals(TripDistance.Reading.Km(12.0, checked = false), TripDistance.interpret(12_000, null))
        assertTrue(TripDistance.interpret(0, 9.0) is TripDistance.Reading.Rejected)
    }
}
