package kr.appdevchallenge.evstay.navi

import ai.pleos.playground.navi.constants.BookmarkType
import ai.pleos.playground.navi.constants.NaviErrorCode
import ai.pleos.playground.navi.constants.RoutePointType
import ai.pleos.playground.navi.data.BookmarkInfo
import ai.pleos.playground.navi.data.CurrentLocationInfo
import ai.pleos.playground.navi.data.RecentDestinationInfo
import ai.pleos.playground.navi.data.TripInfo
import ai.pleos.playground.navi.data.TripRequest
import ai.pleos.playground.navi.helper.NaviHelper
import ai.pleos.playground.navi.helper.listener.NaviHelperEventListener
import android.content.Context
import kr.appdevchallenge.evstay.domain.Coordinate
import kr.appdevchallenge.evstay.domain.HomeCandidates
import kr.appdevchallenge.evstay.domain.TripRequestTracker
import android.util.Log
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/** A place the user treats as home: from the HOME bookmark or picked from recent destinations. */
data class HomePlace(
    val name: String,
    val latitude: Double,
    val longitude: Double,
    val poiId: String,
    val poiSubId: String,
    val fromBookmark: Boolean
)

data class NaviTrip(
    /** The home the trip was requested for; a trip for another place must not be used. */
    val place: HomePlace,
    /** Vehicle SoC when the trip was requested (the navi estimate is based on it). */
    val socAtRequest: Double?,
    /** Raw TripInfo.distance; unit not documented — interpreted by TripDistance. */
    val distanceRaw: Int,
    val estimatedSocAtDest: Int,
    /** Wall-clock time the trip estimate arrived, for staleness. */
    val receivedAtRealMs: Long,
    /** The answer carried a destination point that matched the requested home. */
    val verifiedByDestination: Boolean = false
)

data class NaviState(
    val available: Boolean? = null,
    val error: String? = null,
    val bookmarkHome: HomePlace? = null,
    val recentDestinations: List<HomePlace> = emptyList(),
    val currentLat: Double? = null,
    val currentLon: Double? = null,
    val trip: NaviTrip? = null,
    val tripFailed: Boolean = false,
    /** Why the last trip answer was not used, if it was discarded. */
    val tripDiscardReason: String? = null
)

/**
 * NaviHelper adapter (read-only: get*Info and requestTrip, permission NAVI_ROUTE).
 * Finds home and asks the navigation app for a trip estimate to it.
 */
class HomeRouteSource(context: Context) : NaviHelperEventListener {

    private val appContext = context.applicationContext
    private var helper: NaviHelper? = null
    private val tracker = TripRequestTracker()
    private val placesByKey = mutableMapOf<String, HomePlace>()
    private val _state = MutableStateFlow(NaviState())
    val state: StateFlow<NaviState> = _state.asStateFlow()

    fun start() {
        if (helper != null) return
        val h = try {
            NaviHelper(appContext)
        } catch (t: Throwable) {
            fail("내비 연동 불가: ${t.javaClass.simpleName}")
            return
        }
        try {
            h.initialize({
                helper = h
                runCatching { h.addListener(this) }
                _state.update { it.copy(available = true, error = null) }
                refresh()
            }, { t ->
                runCatching { h.release() }
                fail("내비 연동 불가: ${t.javaClass.simpleName}")
            })
        } catch (t: Throwable) {
            runCatching { h.release() }
            fail("내비 연동 불가: ${t.javaClass.simpleName}")
        }
    }

    fun refresh() {
        val h = helper ?: return
        runCatching {
            h.getBookmarkInfo()
            h.getRecentDestinationInfo()
            h.getCurrentLocationInfo()
        }.onFailure { Log.w(TAG, "navi info request failed", it) }
    }

    /**
     * Sends a trip request unless one is already in flight (responses carry no request id).
     * Returns true when a request was actually sent.
     */
    fun requestTripTo(place: HomePlace, socAtRequest: Double?): Boolean {
        val h = helper ?: return false
        val key = placeKey(place)
        val coordinate = Coordinate(place.latitude, place.longitude)
        if (!tracker.tryStart(key, socAtRequest, System.currentTimeMillis(), coordinate)) return false
        synchronized(placesByKey) { placesByKey[key] = place }
        return runCatching {
            h.requestTrip(TripRequest(place.latitude, place.longitude, place.poiId, place.name, place.poiSubId))
        }.onFailure {
            Log.w(TAG, "requestTrip failed", it)
            tracker.onFailure()
            _state.update { s -> s.copy(trip = null, tripFailed = true) }
        }.isSuccess
    }

    fun release() {
        val h = helper ?: return
        runCatching { h.removeListener(this) }
        runCatching { h.release() }
        helper = null
    }

    override fun onBookmarkInfo(bookmarkInfoList: List<BookmarkInfo>) {
        val home = bookmarkInfoList.firstOrNull { it.category == BookmarkType.HOME }?.let {
            HomePlace(it.name.orEmpty(), it.latitude, it.longitude, it.poiId.orEmpty(), it.poiSubId.orEmpty(), true)
        }
        _state.update { it.copy(bookmarkHome = home) }
    }

    override fun onRecentDestinationInfo(recentDestinationInfoList: List<RecentDestinationInfo>) {
        val recent = recentDestinationInfoList.take(3).map {
            HomePlace(it.name.orEmpty(), it.latitude, it.longitude, it.poiId.orEmpty(), it.poiSubId.orEmpty(), false)
        }
        _state.update { it.copy(recentDestinations = recent) }
    }

    override fun onCurrentLocationInfo(currentLocationInfo: CurrentLocationInfo) {
        _state.update { it.copy(currentLat = currentLocationInfo.latitude, currentLon = currentLocationInfo.longitude) }
    }

    override fun onTripInfo(tripInfo: TripInfo) {
        when (val r = tracker.onResponse(System.currentTimeMillis(), destinationOf(tripInfo))) {
            is TripRequestTracker.Result.Discarded -> {
                Log.w(TAG, "trip answer discarded: ${r.reason}")
                _state.update { it.copy(tripDiscardReason = r.reason) }
            }
            is TripRequestTracker.Result.Accepted -> {
                val place = synchronized(placesByKey) { placesByKey[r.placeKey] } ?: return
                _state.update {
                    it.copy(
                        trip = NaviTrip(place, r.socAtRequest, tripInfo.distance, tripInfo.estimatedSocAtDest,
                            System.currentTimeMillis(), r.verifiedByDestination),
                        tripFailed = false,
                        tripDiscardReason = null
                    )
                }
            }
        }
    }

    /** Drops the cached trip and marks any in-flight request stale (home destination changed). */
    fun clearTrip() {
        tracker.destinationChanged()
        _state.update { it.copy(trip = null, tripFailed = false, tripDiscardReason = null) }
    }

    override fun onBookMarkChanged() {
        refresh()
    }

    override fun onError(errorCode: NaviErrorCode) {
        Log.w(TAG, "navi error $errorCode")
        if (errorCode == NaviErrorCode.CAUSE_SEARCH_TRIP) {
            tracker.onFailure()
            _state.update { it.copy(trip = null, tripFailed = true) }
        }
    }

    /** DESTINATION route point of the answer, if the navigation app filled one (not verified). */
    private fun destinationOf(tripInfo: TripInfo): Coordinate? = runCatching {
        tripInfo.summary.routePoints
            .firstOrNull { it.pointType == RoutePointType.DESTINATION }
            ?.let { Coordinate(it.latitude, it.longitude) }
            ?.takeIf { HomeCandidates.isValid(it) }
    }.getOrNull()

    private fun fail(message: String) {
        helper = null
        _state.update { it.copy(available = false, error = message) }
    }

    companion object {
        private const val TAG = "EvStayNavi"

        fun placeKey(place: HomePlace) = "${place.latitude},${place.longitude},${place.poiId}"
    }
}
