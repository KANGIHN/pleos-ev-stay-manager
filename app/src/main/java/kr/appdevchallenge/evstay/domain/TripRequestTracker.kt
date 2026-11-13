package kr.appdevchallenge.evstay.domain

/**
 * NaviHelper's onTripInfo carries no request id, so responses are attributed in two ways:
 *
 * 1. **Destination match (when the answer carries one).** TripInfo.summary.routePoints may hold a
 *    DESTINATION point. If it does, the answer is used only when that point lies within
 *    [destinationMatchKm] of the requested home; an answer whose destination matches an older
 *    (timed-out) request is discarded. Whether the navigation app fills routePoints is not
 *    verified on the emulator.
 * 2. **Order (when it does not).** Exactly one request is in flight at a time, each with a
 *    sequence number. A request with no answer within [timeoutMs] becomes an orphan; no new
 *    request is sent until the orphan answers (that answer is discarded) or [lateAnswerWindowMs]
 *    has passed since it was sent. After that the orphan is assumed never to answer.
 *
 * Residual risk (order-only case): an answer that arrives more than [lateAnswerWindowMs] after
 * its request, without a destination point, while a newer request is in flight, is attributed to
 * the newer request. The straight-line distance check in [TripDistance] is then the only guard,
 * and it cannot tell apart two destinations at a similar distance.
 */
class TripRequestTracker(
    private val timeoutMs: Long = 30_000,
    private val lateAnswerWindowMs: Long = 600_000,
    private val destinationMatchKm: Double = 1.0
) {
    data class InFlight(
        val seq: Long,
        val placeKey: String,
        val socAtRequest: Double?,
        val sentAtMs: Long,
        val place: Coordinate? = null,
        val stale: Boolean = false
    )

    sealed interface Result {
        /** [verifiedByDestination] = the answer's own destination matched the requested home. */
        data class Accepted(
            val placeKey: String,
            val socAtRequest: Double?,
            val seq: Long = 0,
            val verifiedByDestination: Boolean = false
        ) : Result
        data class Discarded(val reason: String) : Result
    }

    var inFlight: InFlight? = null
        private set
    private var orphan: InFlight? = null
    private var nextSeq = 1L

    /** Returns true when a request for [placeKey] may be sent now; records it as in flight. */
    @Synchronized
    fun tryStart(placeKey: String, socAtRequest: Double?, nowMs: Long, place: Coordinate? = null): Boolean {
        expire(nowMs)
        if (inFlight != null || orphan != null) return false
        inFlight = InFlight(nextSeq++, placeKey, socAtRequest, nowMs, place)
        return true
    }

    /** The home changed: an answer to the in-flight request must not be used. */
    @Synchronized
    fun destinationChanged() {
        inFlight = inFlight?.copy(stale = true)
    }

    /**
     * [destination] is the DESTINATION route point of the answer, or null when the answer has none.
     */
    @Synchronized
    fun onResponse(nowMs: Long, destination: Coordinate? = null): Result {
        expire(nowMs)
        val current = inFlight
        val old = orphan
        if (destination != null) {
            if (current != null && matches(current, destination)) {
                inFlight = null
                if (current.stale) return Result.Discarded("목적지가 바뀌기 전 요청의 응답")
                return Result.Accepted(current.placeKey, current.socAtRequest, current.seq, verifiedByDestination = true)
            }
            if (old != null && matches(old, destination)) {
                orphan = null
                return Result.Discarded("시간 초과 뒤 늦게 도착한 응답(목적지 대조)")
            }
            // Not ours: keep waiting for the real answer.
            return Result.Discarded("응답 목적지가 요청한 집과 다름")
        }
        if (old != null) {
            // While an orphan may still answer no new request is sent, so this is the orphan's answer.
            orphan = null
            return Result.Discarded("시간 초과 뒤 늦게 도착한 응답")
        }
        if (current == null) return Result.Discarded("요청하지 않은 응답")
        inFlight = null
        if (current.stale) return Result.Discarded("목적지가 바뀌기 전 요청의 응답")
        return Result.Accepted(current.placeKey, current.socAtRequest, current.seq)
    }

    @Synchronized
    fun onFailure() {
        inFlight = null
    }

    private fun matches(request: InFlight, destination: Coordinate): Boolean {
        val p = request.place ?: return false
        return Units.haversineKm(p.latitude, p.longitude, destination.latitude, destination.longitude) <= destinationMatchKm
    }

    private fun expire(nowMs: Long) {
        val current = inFlight
        if (current != null && nowMs - current.sentAtMs > timeoutMs) {
            inFlight = null
            orphan = current
        }
        val o = orphan
        if (o != null && nowMs - o.sentAtMs > lateAnswerWindowMs) orphan = null
    }
}

/**
 * TripInfo.distance is an Int with no documented unit. It is read as meters (the convention of
 * the AAOS distance properties) only if that is consistent with the straight-line distance to
 * the requested home; otherwise the answer is not trusted.
 */
object TripDistance {
    sealed interface Reading {
        data class Km(val km: Double, val checked: Boolean) : Reading
        data class Rejected(val reason: String) : Reading
    }

    fun interpret(raw: Int, straightLineKm: Double?): Reading {
        if (raw <= 0) return Reading.Rejected("경로 거리가 0 이하")
        val km = raw / 1000.0
        if (straightLineKm == null || straightLineKm < 0.5) return Reading.Km(km, checked = false)
        return if (km >= straightLineKm * 0.9 && km <= straightLineKm * 4.0) Reading.Km(km, checked = true)
        else Reading.Rejected("경로 거리 ${String.format(java.util.Locale.ROOT, "%.1f", km)}km가 직선거리 ${String.format(java.util.Locale.ROOT, "%.1f", straightLineKm)}km와 맞지 않음(미터 해석 기준)")
    }
}
