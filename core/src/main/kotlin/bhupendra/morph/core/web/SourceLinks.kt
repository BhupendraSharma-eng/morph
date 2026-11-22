package bhupendra.morph.core.web

/**
 * The links Morph's web tools have returned recently (headline/citation title → URL). Cards built from them should
 * carry their URLs; when a model forgets, [find] recovers the link by matching a row's words to a source title.
 */
object SourceLinks {
    private const val MAX = 80
    private val recent = ArrayDeque<Pair<String, String>>()

    @Synchronized fun record(title: String, url: String) {
        if (!url.startsWith("http")) return
        recent.removeAll { it.second == url }
        recent.addLast(title to url)
        while (recent.size > MAX) recent.removeFirst()
    }

    /** The recorded source whose title best matches [text] (enough shared words), or null. */
    @Synchronized fun find(text: String): String? {
        val words = words(text).ifEmpty { return null }
        return recent.asReversed().map { (t, u) ->
            val w = words(t); val shared = (words intersect w).size
            u to if (w.isEmpty()) 0.0 else shared.toDouble() / minOf(words.size, w.size)
        }.filter { it.second >= 0.5 }.maxByOrNull { it.second }?.first
    }

    private val STOP = setOf("the", "a", "an", "of", "to", "in", "on", "for", "and", "is", "with", "as", "at", "by", "from", "its", "it")
    private fun words(s: String) = s.lowercase().split(Regex("[^\\p{L}\\p{N}]+")).filter { it.length > 2 && it !in STOP }.toSet()
}
