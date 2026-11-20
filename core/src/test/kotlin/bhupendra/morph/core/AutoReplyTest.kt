package bhupendra.morph.core

import bhupendra.morph.core.automation.IncomingMessage
import bhupendra.morph.core.automation.RateLimiter
import bhupendra.morph.core.automation.ReplyMode
import bhupendra.morph.core.automation.ReplyRule
import bhupendra.morph.core.automation.RuleMatcher
import bhupendra.morph.core.automation.VerdictParser
import bhupendra.morph.core.automation.rulesFromJson
import bhupendra.morph.core.automation.rulesToJson
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.LocalTime

class AutoReplyTest {
    private val mom = IncomingMessage("com.whatsapp", "WhatsApp", "Mom", "Mom", "Where are you?", isGroup = false)

    @Test fun `matches sender, app and keyword case-insensitively`() {
        val rule = ReplyRule(appPackage = "com.whatsapp", sender = "mom", keyword = "where")
        assertTrue(RuleMatcher.matches(rule, mom))
        assertFalse(RuleMatcher.matches(rule.copy(appPackage = "org.telegram.messenger"), mom))
        assertFalse(RuleMatcher.matches(rule.copy(keyword = "dinner"), mom))
    }

    @Test fun `groups are skipped unless the rule opts in, but watch mode always sees them`() {
        val group = mom.copy(conversation = "Family", isGroup = true)
        assertFalse(RuleMatcher.matches(ReplyRule(), group))
        assertTrue(RuleMatcher.matches(ReplyRule(includeGroups = true), group))
        assertTrue(RuleMatcher.matches(ReplyRule(mode = ReplyMode.Watch), group))
    }

    @Test fun `time windows can wrap past midnight`() {
        assertTrue(RuleMatcher.inWindow("22:00-07:00", LocalTime.of(23, 30)))
        assertTrue(RuleMatcher.inWindow("22:00-07:00", LocalTime.of(6, 0)))
        assertFalse(RuleMatcher.inWindow("22:00-07:00", LocalTime.of(12, 0)))
        assertTrue(RuleMatcher.inWindow("9:00-17:00", LocalTime.of(9, 30)))
    }

    @Test fun `rate limiter blocks duplicates, bursts and hourly floods`() {
        var now = 0L
        val rl = RateLimiter(minGapMs = 45_000, maxPerHour = 2, clock = { now })
        assertTrue(rl.allow("t", "hi")); rl.record("t", "hi")
        assertFalse(rl.allow("t", "hi"))            // same text
        now += 10_000; assertFalse(rl.allow("t", "again"))   // too soon
        now += 60_000; assertTrue(rl.allow("t", "again")); rl.record("t", "again")
        now += 60_000; assertFalse(rl.allow("t", "third"))   // hourly cap
        now += 3_700_000; assertTrue(rl.allow("t", "later"))
    }

    @Test fun `verdict parses json, legacy urgent tag and plain text`() {
        val j = VerdictParser.parse("""```json
            {"urgent": true, "reason": "Accident", "reply": "I've alerted him, he'll call right away."}
        ```""")
        assertTrue(j.urgent); assertEquals("Accident", j.reason)
        val legacy = VerdictParser.parse("[URGENT: chest pain] Calling him now, hang on.")
        assertTrue(legacy.urgent); assertEquals("chest pain", legacy.reason); assertEquals("Calling him now, hang on.", legacy.reply)
        val plain = VerdictParser.parse("\"On my way!\"")
        assertFalse(plain.urgent); assertNull(plain.reason); assertEquals("On my way!", plain.reply)
    }

    @Test fun `rules round-trip through json`() {
        val rules = listOf(ReplyRule(sender = "Mom", mode = ReplyMode.Fixed, text = "Driving!", window = "18:00-19:00"))
        assertEquals(rules, rulesFromJson(rulesToJson(rules)))
    }
}
