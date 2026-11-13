package kr.appdevchallenge.evstay.domain

/**
 * App clock. In demo mode one real second advances [demoSpeed] seconds, so a slow battery
 * drain can be shown in a few minutes. The screen must show the "시연 모드" badge whenever
 * [isDemo] is true.
 */
class StayClock(
    private val realNowMs: () -> Long,
    val demoSpeed: Int = 60
) {
    private var anchorRealMs = realNowMs()
    private var anchorAppMs = anchorRealMs
    var isDemo: Boolean = false
        private set

    @Synchronized
    fun nowMs(): Long {
        val speed = if (isDemo) demoSpeed else 1
        return anchorAppMs + (realNowMs() - anchorRealMs) * speed
    }

    @Synchronized
    fun setDemo(enabled: Boolean) {
        if (enabled == isDemo) return
        val now = nowMs()
        anchorRealMs = realNowMs()
        anchorAppMs = now
        isDemo = enabled
    }
}
