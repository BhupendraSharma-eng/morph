package bhupendra.morph.core.text

/**
 * Matching names the way they're said, not spelled: speech recognition writes "Poorab" for a contact saved as "Purab",
 * and Indian names have many spellings (Purav/Purab, Snehaa/Sneha, Aashish/Ashish, Vikas/Wikas). Both sides are
 * folded to a rough sound key, then compared per word with a small edit tolerance.
 */
object NameMatch {
    /** A rough phonetic key: lowercase letters only, Hinglish spelling variants folded together. */
    fun key(s: String): String {
        var k = s.lowercase().replace(Regex("[^a-z ]"), " ").trim()
        listOf("ph" to "f", "w" to "v", "z" to "j", "q" to "k", "ck" to "k", "x" to "ks",
            "oo" to "u", "ou" to "u", "ee" to "i", "aa" to "a", "ii" to "i", "uu" to "u", "y" to "i").forEach { (a, b) -> k = k.replace(a, b) }
        k = k.replace(Regex("([a-z])h"), "$1")          // aspiration: bh→b, dh→d, sh→s, th→t…
        k = k.replace(Regex("([a-z])\\1+"), "$1")       // doubled letters
        k = k.replace(Regex("(?<=[a-z])[aeiou]+(?=\\b)"), "")   // trailing vowels (Sneha / Snehaa / Sneh)
        k = k.replace(Regex("b(?=\\b)"), "v")            // final b/v (Purab / Purav)
        return k.replace(Regex("\\s+"), " ")
    }

    /**
     * 0..1: how well [query] matches [name]. Every word of the query must match some word of the name (exact key,
     * prefix, or within one edit for words of 4+ letters).
     */
    fun score(query: String, name: String): Double {
        val q = key(query).split(' ').filter { it.isNotBlank() }
        val n = key(name).split(' ').filter { it.isNotBlank() }
        if (q.isEmpty() || n.isEmpty()) return 0.0
        var total = 0.0
        for (w in q) {
            val best = n.maxOf { m ->
                when {
                    m == w -> 1.0
                    m.startsWith(w) && w.length >= 3 -> 0.85
                    w.length >= 4 && edits(w, m) <= 1 -> 0.75
                    w.length >= 6 && edits(w, m) <= 2 -> 0.6
                    else -> 0.0
                }
            }
            if (best == 0.0) return 0.0
            total += best
        }
        return total / q.size
    }

    private fun edits(a: String, b: String): Int {
        if (kotlin.math.abs(a.length - b.length) > 2) return 3
        val d = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = d[0]; d[0] = i
            for (j in 1..b.length) {
                val cur = d[j]
                d[j] = minOf(d[j] + 1, d[j - 1] + 1, prev + if (a[i - 1] == b[j - 1]) 0 else 1)
                prev = cur
            }
        }
        return d[b.length]
    }
}
