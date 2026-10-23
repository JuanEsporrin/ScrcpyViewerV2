package com.programmersbox.scrcpyviewer.presets

enum class ConflictChoice { Replace, KeepBoth, Skip }

data class ImportItem(
    val preset: Preset,
    val conflictsWith: Preset?,
    val choice: ConflictChoice,
    val include: Boolean,
)

object ImportPlan {
    /** Builds one item per incoming preset. A name clash (case-insensitive) defaults to [ConflictChoice.Replace]. */
    fun plan(existing: List<Preset>, incoming: List<Preset>): List<ImportItem> = incoming.map { p ->
        val clash = existing.firstOrNull { it.name.equals(p.name, ignoreCase = true) }
        ImportItem(p, clash, ConflictChoice.Replace, include = true)
    }

    fun apply(existing: List<Preset>, items: List<ImportItem>): List<Preset> {
        val result = existing.toMutableList()
        for (item in items) {
            if (!item.include) continue
            val conflict = item.conflictsWith
            if (conflict == null) {
                result += item.preset
                continue
            }
            when (item.choice) {
                ConflictChoice.Skip -> Unit
                ConflictChoice.Replace -> {
                    val i = result.indexOfFirst { it.id == conflict.id }
                    val replaced = item.preset.copy(id = conflict.id)
                    if (i >= 0) result[i] = replaced else result += replaced
                }
                ConflictChoice.KeepBoth -> result += item.preset.copy(name = uniqueName(item.preset.name, result))
            }
        }
        return result
    }

    private fun uniqueName(base: String, taken: List<Preset>): String {
        var n = 2
        while (taken.any { it.name.equals("$base ($n)", ignoreCase = true) }) n++
        return "$base ($n)"
    }
}
