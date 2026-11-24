package bhupendra.morphy.data

import android.content.Context
import bhupendra.morphy.core.llm.UsageKind
import bhupendra.morphy.core.llm.UsageMeter
import bhupendra.morphy.core.llm.UsageRecord
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.YearMonth
import java.time.ZoneId

/** Running totals for one slice (all time, a month, a kind, a model). */
data class UsageTotals(val calls: Int = 0, val inTokens: Long = 0, val outTokens: Long = 0, val usd: Double = 0.0, val unpriced: Int = 0) {
    operator fun plus(r: UsageRecord) = UsageTotals(calls + 1, inTokens + r.inTokens, outTokens + r.outTokens,
        usd + (r.costUsd ?: 0.0), unpriced + if (r.costUsd == null) 1 else 0)
}

data class UsageSummary(
    val allTime: UsageTotals = UsageTotals(),
    val thisMonth: UsageTotals = UsageTotals(),
    val byKind: Map<UsageKind, UsageTotals> = emptyMap(),
    val byModel: Map<String, UsageTotals> = emptyMap(),
    /** Newest first. */
    val recent: List<UsageRecord> = emptyList(),
    val since: Long = System.currentTimeMillis(),
)

/**
 * Every model call Morphy makes (installed as [UsageMeter.sink]): the last [KEEP] calls in a JSON-lines file for the
 * list, and all-time totals (overall, per kind, per model) kept separately so trimming the list never loses them.
 */
class UsageLedger(context: Context, private val scope: CoroutineScope) {
    private val file = File(context.filesDir, "usage.jsonl")
    private val totalsFile = File(context.filesDir, "usage-totals.json")
    private val _summary = MutableStateFlow(UsageSummary())
    val summary: StateFlow<UsageSummary> = _summary

    init {
        scope.launch(Dispatchers.IO) { _summary.value = load() }
        UsageMeter.sink = ::add
    }

    @Synchronized private fun add(r: UsageRecord) {
        val s = _summary.value
        val month = r.at.month() == YearMonth.now()
        _summary.value = s.copy(
            allTime = s.allTime + r,
            thisMonth = (if (month) s.thisMonth else UsageTotals()) + r,
            byKind = s.byKind + (r.kind to ((s.byKind[r.kind] ?: UsageTotals()) + r)),
            byModel = s.byModel + (r.model to ((s.byModel[r.model] ?: UsageTotals()) + r)),
            recent = (listOf(r) + s.recent).take(KEEP),
        )
        val snapshot = _summary.value
        scope.launch(Dispatchers.IO) { persist(r, snapshot) }
    }

    @Synchronized private fun persist(r: UsageRecord, s: UsageSummary) = runCatching {
        file.appendText(encode(r).toString() + "\n")
        if (file.length() > 1_500_000) file.writeText(s.recent.asReversed().joinToString("") { encode(it).toString() + "\n" })
        totalsFile.writeText(JSONObject().apply {
            put("since", s.since)
            put("all", totals(s.allTime))
            put("monthId", YearMonth.now().toString()); put("month", totals(s.thisMonth))
            put("kinds", JSONObject().apply { s.byKind.forEach { (k, t) -> put(k.name, totals(t)) } })
            put("models", JSONObject().apply { s.byModel.forEach { (m, t) -> put(m, totals(t)) } })
        }.toString())
    }

    private fun load(): UsageSummary {
        val recent = runCatching { file.readLines().mapNotNull { decode(it) } }.getOrDefault(emptyList()).asReversed().take(KEEP)
        val t = runCatching { JSONObject(totalsFile.readText()) }.getOrNull()
        val now = YearMonth.now()
        return UsageSummary(
            allTime = t?.optJSONObject("all")?.let(::totals) ?: recent.fold(UsageTotals()) { a, r -> a + r },
            thisMonth = t?.takeIf { it.optString("monthId") == now.toString() }?.optJSONObject("month")?.let(::totals)
                ?: recent.filter { it.at.month() == now }.fold(UsageTotals()) { a, r -> a + r },
            byKind = t?.optJSONObject("kinds")?.let { o -> o.keys().asSequence().mapNotNull { k ->
                runCatching { UsageKind.valueOf(k) }.getOrNull()?.let { it to totals(o.getJSONObject(k)) } }.toMap() } ?: emptyMap(),
            byModel = t?.optJSONObject("models")?.let { o -> o.keys().asSequence().associateWith { totals(o.getJSONObject(it)) } } ?: emptyMap(),
            recent = recent,
            since = t?.optLong("since")?.takeIf { it > 0 } ?: recent.lastOrNull()?.at ?: System.currentTimeMillis(),
        )
    }

    private fun encode(r: UsageRecord) = JSONObject().apply {
        put("at", r.at); put("k", r.kind.name); put("m", r.model); put("i", r.inTokens); put("o", r.outTokens)
        r.costUsd?.let { put("c", it) }; if (r.estimated) put("e", true)
        r.ms?.let { put("ms", it) }; r.firstMs?.let { put("f", it) }; if (r.cachedTokens > 0) put("ca", r.cachedTokens)
    }

    private fun decode(line: String): UsageRecord? = runCatching {
        val o = JSONObject(line)
        UsageRecord(o.getLong("at"), UsageKind.valueOf(o.getString("k")), o.getString("m"), o.optInt("i"), o.optInt("o"),
            if (o.has("c")) o.getDouble("c") else null, o.optBoolean("e"),
            if (o.has("ms")) o.getLong("ms") else null, if (o.has("f")) o.getLong("f") else null, o.optInt("ca"))
    }.getOrNull()

    private fun totals(t: UsageTotals) = JSONObject().apply {
        put("calls", t.calls); put("in", t.inTokens); put("out", t.outTokens); put("usd", t.usd); put("unpriced", t.unpriced)
    }
    private fun totals(o: JSONObject) = UsageTotals(o.optInt("calls"), o.optLong("in"), o.optLong("out"), o.optDouble("usd", 0.0), o.optInt("unpriced"))

    private fun Long.month() = YearMonth.from(java.time.Instant.ofEpochMilli(this).atZone(ZoneId.systemDefault()))

    companion object { const val KEEP = 2000 }
}
