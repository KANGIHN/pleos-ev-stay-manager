package kr.appdevchallenge.evstay.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeCandidatesTest {

    private val home = Coordinate(37.40, 127.10)
    private val here = Coordinate(37.45, 127.13)
    private val trip = TripSnapshot("home", distanceKm = 12.0, estimatedSocAtDest = 30, socAtRequest = 34.0, distanceChecked = true)
    private val unverified = trip.copy(distanceChecked = false, verifiedByDestination = false)

    @Test
    fun validTrip_comesFirstInOrder() {
        val r = HomeCandidates.build(trip, false, "home", home, here, 35.0, 40.0)
        assertEquals(HomeEnergy.NaviSocAtDest(31.0), r.candidates[0])
        assertEquals(HomeEnergy.Distance(12.0, DistanceSource.NAVI_TRIP), r.candidates[1])
        assertEquals(DistanceSource.MANUAL, (r.candidates[2] as HomeEnergy.Distance).source)
        assertEquals(DistanceSource.STRAIGHT_LINE, (r.candidates[3] as HomeEnergy.Distance).source)
    }

    @Test
    fun failedTrip_isNotUsed() {
        val r = HomeCandidates.build(trip, true, "home", home, here, 35.0, null)
        assertTrue(r.candidates.none { it is HomeEnergy.NaviSocAtDest })
        assertTrue(r.candidates.none { it is HomeEnergy.Distance && it.source == DistanceSource.NAVI_TRIP })
    }

    @Test
    fun tripForPreviousHome_isNotUsed() {
        val r = HomeCandidates.build(trip, false, "new-home", home, here, 35.0, null)
        assertTrue(r.candidates.none { it is HomeEnergy.NaviSocAtDest })
        assertEquals(1, r.candidates.size)
    }

    @Test
    fun invalidCoordinates_areReportedNotUsed() {
        val r = HomeCandidates.build(null, false, "home", Coordinate(0.0, 0.0), here, 35.0, null)
        assertTrue(r.candidates.isEmpty())
        assertEquals("즐겨찾기 집 좌표", r.issues.single().label)
        val r2 = HomeCandidates.build(null, false, "home", home, Coordinate(95.0, 10.0), 35.0, null)
        assertTrue(r2.candidates.isEmpty())
        assertEquals("내비 현재 위치", r2.issues.single().label)
    }

    @Test
    fun unverifiedTrip_ranksBelowManual_andIsMarked() {
        val r = HomeCandidates.build(unverified, false, "home", home, here, 35.0, 40.0)
        assertEquals(HomeEnergy.Distance(40.0, DistanceSource.MANUAL), r.candidates[0])
        assertEquals(HomeEnergy.NaviSocAtDest(31.0, verified = false), r.candidates[1])
        assertEquals(HomeEnergy.Distance(12.0, DistanceSource.NAVI_TRIP, verified = false), r.candidates[2])
        // Without a manual distance the unverified answer is still first, but marked.
        val noManual = HomeCandidates.build(unverified, false, "home", home, here, 35.0, null)
        assertEquals(HomeEnergy.NaviSocAtDest(31.0, verified = false), noManual.candidates[0])
        // Destination match alone also makes it trusted.
        val byDest = HomeCandidates.build(unverified.copy(verifiedByDestination = true), false, "home", home, here, 35.0, 40.0)
        assertEquals(HomeEnergy.NaviSocAtDest(31.0), byDest.candidates[0])
    }

    @Test
    fun expiredTrip_isNotUsedAndReported() {
        val old = trip.copy(ageMs = HomeCandidates.TRIP_MAX_AGE_MS + 1)
        val r = HomeCandidates.build(old, false, "home", home, here, 35.0, null)
        assertTrue(r.candidates.none { it is HomeEnergy.NaviSocAtDest })
        assertTrue(r.issues.single().reason.contains("만료"))
        val fresh = HomeCandidates.build(trip.copy(ageMs = HomeCandidates.TRIP_MAX_AGE_MS), false, "home", home, here, 35.0, null)
        assertEquals(HomeEnergy.NaviSocAtDest(31.0), fresh.candidates[0])
    }
}
