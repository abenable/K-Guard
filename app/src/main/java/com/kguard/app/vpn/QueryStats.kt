package com.kguard.app.vpn

/**
 * What K-Guard keeps about one day of DNS lookups. Counts only, never domains.
 * [flagged] maps a category id to the number of lookups that matched that category's list,
 * whether they were blocked or allowed by a parent's rule.
 */
data class DayCounts(
    val queries: Int = 0,
    val blocked: Int = 0,
    val flagged: Map<String, Int> = emptyMap()
) {
    fun withQuery(wasBlocked: Boolean, category: String?) = DayCounts(
        queries = queries + 1,
        blocked = blocked + if (wasBlocked) 1 else 0,
        flagged = if (category == null) flagged else flagged + (category to ((flagged[category] ?: 0) + 1))
    )

    /** Compact form for SharedPreferences: `queries,blocked,cat=n;cat=n`. */
    fun encode(): String = "$queries,$blocked," + flagged.entries.joinToString(";") { "${it.key}=${it.value}" }

    companion object {
        fun decode(text: String?): DayCounts = runCatching {
            val parts = text!!.split(',', limit = 3)
            DayCounts(
                queries = parts[0].toInt(),
                blocked = parts[1].toInt(),
                flagged = parts.getOrElse(2) { "" }.split(';').filter { '=' in it }
                    .associate { it.substringBefore('=') to it.substringAfter('=').toInt() }
            )
        }.getOrDefault(DayCounts())
    }
}

/** Nearest-rank percentile of an ascending list (p in 1..100). Empty list gives 0. */
fun percentile(sorted: List<Long>, p: Int): Long {
    if (sorted.isEmpty()) return 0
    val rank = Math.ceil(p / 100.0 * sorted.size).toInt()
    return sorted[(rank - 1).coerceIn(0, sorted.size - 1)]
}
