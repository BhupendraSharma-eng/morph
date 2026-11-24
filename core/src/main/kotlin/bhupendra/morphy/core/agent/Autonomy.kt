package bhupendra.morphy.core.agent

/**
 * How much the agent does on its own. One dial for every guardrail, so they can be loosened for
 * exploration and reintroduced gently later without touching code.
 *
 * Floors that hold at every level: spending money needs the user's tap, and passwords/PINs/OTPs are
 * never typed. Everything is recorded in the activity ledger regardless.
 */
enum class Autonomy(
    val label: String,
    val blurb: String,
    /** Max model rounds in one conversational turn. */
    val agentRounds: Int,
    /** Max steps for one operate_phone task. */
    val operatorSteps: Int,
) {
    Careful("Careful", "Asks before sending, posting, deleting, and before acting on anything it read online", 8, 30),
    Balanced("Balanced", "Asks before sending, posting or deleting; otherwise gets on with it", 16, 60),
    Autonomous("Autonomous", "Carries tasks through end to end. Only spending money needs your tap", 40, 100);

    /** Taps the operator code itself refuses to perform without the user's approval. */
    val operatorConfirm: Regex
        get() = when (this) {
            Careful -> Regex("\\b(send|post|delete|remove|book|confirm|submit|unsubscribe|sign out|log ?out|$MONEY)\\b", RegexOption.IGNORE_CASE)
            Balanced -> Regex("\\b(send|post|delete|remove|$MONEY)\\b", RegexOption.IGNORE_CASE)
            Autonomous -> Regex("\\b($MONEY)\\b", RegexOption.IGNORE_CASE)
        }

    /** Whether a state-changing tool needs approval after the turn has read untrusted content. */
    fun gatesAfterUntrusted(spec: ToolSpec): Boolean = when (this) {
        Careful -> spec.risk != Risk.Read
        Balanced -> spec.risk == Risk.Handoff || spec.name == "operate_phone"
        Autonomous -> false   // the money floor is enforced inside the operator instead
    }

    companion object {
        const val MONEY = "pay|payment|pay now|buy|buy now|purchase|place order|order now|checkout|check out|transfer|" +
            "send money|add money|proceed to pay|subscribe|upgrade plan|donate"
        fun parse(s: String?): Autonomy = entries.firstOrNull { it.name.equals(s, true) } ?: Autonomous
    }
}
