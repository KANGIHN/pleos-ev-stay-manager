package kr.appdevchallenge.evstay.domain

/** Navigation trip estimate, already tied to the home it was requested for. */
data class TripSnapshot(
    val placeKey: String,
    val distanceKm: Double?,
    val estimatedSocAtDest: Int,
    val socAtRequest: Double?,
    /** The answer's own destination point matched the requested home. */
    val verifiedByDestination: Boolean = false,
    /** The route distance was cross-checked against the straight-line distance to the home. */
    val distanceChecked: Boolean = false,
    /** Time since the answer arrived (wall clock). */
    val ageMs: Long = 0
) {
    val trusted: Boolean get() = verifiedByDestination || distanceChecked
}

data class Coordinate(val latitude: Double, val longitude: Double)

data class HomeInputs(val candidates: List<HomeEnergy>, val issues: List<RejectedHome>)

/**
 * Builds the ordered home-energy candidates. A cached navigation trip is used only when the
 * last request did not fail and it was made for the current home; otherwise it is ignored so
 * the screen never shows an old route after "경로 예상 실패" or a destination change.
 */
object HomeCandidates {

    fun build(
        trip: TripSnapshot?,
        tripFailed: Boolean,
        homeKey: String?,
        home: Coordinate?,
        current: Coordinate?,
        currentSoc: Double?,
        manualKm: Double?,
        maxTripAgeMs: Long = TRIP_MAX_AGE_MS
    ): HomeInputs {
        val issues = mutableListOf<RejectedHome>()
        val candidates = mutableListOf<HomeEnergy>()

        val forThisHome = trip?.takeIf { !tripFailed && homeKey != null && it.placeKey == homeKey }
        val usableTrip = forThisHome?.takeIf { it.ageMs <= maxTripAgeMs }
        if (forThisHome != null && usableTrip == null) {
            issues += RejectedHome("내비 경로 예상", "응답을 받은 지 ${forThisHome.ageMs / 60_000}분이 지나 만료(${maxTripAgeMs / 60_000}분)")
        }
        val tripCandidates = mutableListOf<HomeEnergy>()
        if (usableTrip != null) {
            val trusted = usableTrip.trusted
            val requested = usableTrip.socAtRequest
            if (requested != null && currentSoc != null) {
                val need = requested - usableTrip.estimatedSocAtDest
                tripCandidates += HomeEnergy.NaviSocAtDest(currentSoc - need, verified = trusted)
            }
            usableTrip.distanceKm?.takeIf { it > 0 }?.let { tripCandidates += HomeEnergy.Distance(it, DistanceSource.NAVI_TRIP, verified = trusted) }
        }
        val manual = manualKm?.let { HomeEnergy.Distance(it, DistanceSource.MANUAL) }
        // A trusted navi answer comes first; an unconfirmed one ranks below a directly entered distance.
        if (usableTrip?.trusted == false) {
            manual?.let { candidates += it }
            candidates += tripCandidates
        } else {
            candidates += tripCandidates
            manual?.let { candidates += it }
        }

        if (home != null) {
            when {
                !isValid(home) -> issues += RejectedHome("즐겨찾기 집 좌표", "좌표가 유효하지 않음(0,0 또는 범위 밖)")
                current == null -> Unit
                !isValid(current) -> issues += RejectedHome("내비 현재 위치", "좌표가 유효하지 않음(0,0 또는 범위 밖)")
                else -> candidates += HomeEnergy.Distance(
                    Units.haversineKm(current.latitude, current.longitude, home.latitude, home.longitude),
                    DistanceSource.STRAIGHT_LINE
                )
            }
        }
        return HomeInputs(candidates, issues)
    }

    /** A cached navi trip estimate older than this is not used (requests are refreshed every 5 minutes). */
    const val TRIP_MAX_AGE_MS = 15 * 60_000L

    fun isValid(c: Coordinate): Boolean =
        c.latitude.isFinite() && c.longitude.isFinite() &&
            c.latitude in -90.0..90.0 && c.longitude in -180.0..180.0 &&
            !(c.latitude == 0.0 && c.longitude == 0.0)
}
