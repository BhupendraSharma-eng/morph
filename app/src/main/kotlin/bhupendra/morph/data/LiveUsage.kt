package bhupendra.morph.data

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import java.time.YearMonth

/** This month's hands-free use. */
data class LiveMonth(val month: String, val seconds: Long = 0, val inTokens: Long = 0, val outTokens: Long = 0) {
    /** Estimated cost in USD (see [LiveUsage] for the rates). */
    val usd: Double get() = inTokens * LiveUsage.IN_PER_TOKEN + outTokens * LiveUsage.OUT_PER_TOKEN
    val minutes: Long get() = (seconds + 30) / 60
}

/**
 * Cost meter for Gemini Live: tokens from each turn's usage report, minutes on air, per calendar month (resets on
 * the 1st). Rates are estimates — input is Google's published Live audio-input price, the output rate is assumed
 * until a Gemini API price is published — so the UI always says "≈".
 */
class LiveUsage(context: Context) {
    private val prefs = context.getSharedPreferences("live_usage", Context.MODE_PRIVATE)
    private val _month = MutableStateFlow(load())
    val month: StateFlow<LiveMonth> = _month

    @Synchronized fun add(seconds: Long = 0, inTokens: Int = 0, outTokens: Int = 0) {
        val cur = _month.value.takeIf { it.month == thisMonth() } ?: LiveMonth(thisMonth())
        val next = cur.copy(seconds = cur.seconds + seconds, inTokens = cur.inTokens + inTokens, outTokens = cur.outTokens + outTokens)
        _month.value = next
        prefs.edit().putString("month", next.month).putLong("seconds", next.seconds)
            .putLong("in", next.inTokens).putLong("out", next.outTokens).apply()
    }

    private fun load(): LiveMonth {
        val m = prefs.getString("month", null)
        return if (m != thisMonth()) LiveMonth(thisMonth())
        else LiveMonth(m, prefs.getLong("seconds", 0), prefs.getLong("in", 0), prefs.getLong("out", 0))
    }

    private fun thisMonth() = YearMonth.now().toString()

    companion object {
        /** $3.00 / 1M input tokens (Gemini 3.8 Live audio input, Vertex AI list price, Nov 2026). */
        const val IN_PER_TOKEN = 3.00 / 1_000_000
        /** Assumed $12.00 / 1M output tokens until Google publishes the Gemini API audio-output rate. */
        const val OUT_PER_TOKEN = 12.00 / 1_000_000
        val CAPS = listOf(2.0, 5.0, 10.0, 0.0)   // 0 = no cap
        /** Cached context is billed at a quarter of the input rate (Gemini's usual discount; assumed for Live). */
        private const val CACHED_FACTOR = 0.25

        fun cost(promptTokens: Int, cachedTokens: Int, responseTokens: Int): Double =
            (promptTokens - cachedTokens).coerceAtLeast(0) * IN_PER_TOKEN + cachedTokens * IN_PER_TOKEN * CACHED_FACTOR +
                responseTokens * OUT_PER_TOKEN
    }
}
