package kr.appdevchallenge.evstay.domain

/**
 * Answers the third question on the first screen — "which setting would cover the planned time" —
 * from the planned-time check and the setting comparisons. Uses the short (conservative) end of
 * the range, the same basis as [TargetCheck].
 */
sealed interface SettingAdvice {
    /** No planned time set. */
    data object NoTarget : SettingAdvice

    /** The planned time has passed. */
    data object Reached : SettingAdvice

    /** Already expected to cover the planned time under the current assumptions. */
    data class AlreadyMet(val marginMinutes: Int) : SettingAdvice

    /** Each of [options] alone is expected to cover the [shortMinutes] gap. */
    data class CoveredBy(val shortMinutes: Int, val options: List<SettingComparison>) : SettingAdvice

    /**
     * The planned time looks covered (now or with a setting change), but the home-return part rests
     * on an unconfirmed navi answer or there is no home information, so "충족 예상" is held.
     */
    data object HeldUnverifiedHome : SettingAdvice

    /** No single compared setting covers the gap; [best] is the largest gain, if any. */
    data class NotCovered(val shortMinutes: Int, val best: SettingComparison?) : SettingAdvice
}

object StayDecision {

    fun advise(target: TargetCheck?, comparisons: List<SettingComparison>): SettingAdvice {
        if (target == null) return SettingAdvice.NoTarget
        if (target.reached) return SettingAdvice.Reached
        if (target.met) return if (target.homeVerdictHeld) SettingAdvice.HeldUnverifiedHome else SettingAdvice.AlreadyMet(target.marginMinutes)
        val short = -target.marginMinutes
        // Decided on unrounded values: a gain that only reaches the shortfall after rounding does not count.
        val shortExact = -target.marginExactMinutes
        // Alternatives that keep the HVAC on come first; otherwise the list order (smallest change first) is kept.
        val covering = comparisons.filter { it.lowDeltaExactMinutes >= shortExact }.sortedByDescending { it.keepsHvacOn }
        if (covering.isNotEmpty()) {
            return if (target.homeVerdictHeld) SettingAdvice.HeldUnverifiedHome else SettingAdvice.CoveredBy(short, covering)
        }
        val gaining = comparisons.filter { it.lowDeltaExactMinutes > 0 }
        val best = gaining.filter { it.keepsHvacOn }.maxByOrNull { it.lowDeltaExactMinutes }
            ?: gaining.maxByOrNull { it.lowDeltaExactMinutes }
        return SettingAdvice.NotCovered(short, best)
    }
}
