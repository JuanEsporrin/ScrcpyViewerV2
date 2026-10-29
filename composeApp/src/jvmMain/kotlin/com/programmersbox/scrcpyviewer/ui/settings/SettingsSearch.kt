package com.programmersbox.scrcpyviewer.ui.settings

data class CategoryMatch(val category: SettingsCategory, val entries: List<SettingEntry>)

/**
 * The categories with at least one entry matching [query], each with its matching entries. A category whose title
 * matches keeps all its entries. Blank (after trim) query → every category with all entries.
 */
fun searchSettings(categories: List<SettingsCategory>, query: String): List<CategoryMatch> {
    val q = query.trim()
    if (q.isEmpty()) return categories.map { CategoryMatch(it, it.entries) }
    return categories.mapNotNull { category ->
        val entries = if (category.title.contains(q, ignoreCase = true)) category.entries
        else category.entries.filter { it.matches(q) }
        if (entries.isEmpty()) null else CategoryMatch(category, entries)
    }
}

private fun SettingEntry.matches(q: String) =
    title.contains(q, ignoreCase = true) ||
        description?.contains(q, ignoreCase = true) == true ||
        keywords.any { it.contains(q, ignoreCase = true) }

/** Case-insensitive, literal, non-overlapping occurrences of trimmed [query] in [text]; empty for a blank query. */
fun matchRanges(text: String, query: String): List<IntRange> {
    val q = query.trim()
    if (q.isEmpty()) return emptyList()
    return buildList {
        var from = 0
        while (true) {
            val i = text.indexOf(q, from, ignoreCase = true)
            if (i < 0) break
            add(i until i + q.length)
            from = i + q.length
        }
    }
}
