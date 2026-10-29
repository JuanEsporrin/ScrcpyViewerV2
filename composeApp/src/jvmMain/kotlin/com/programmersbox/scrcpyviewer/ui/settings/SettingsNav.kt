package com.programmersbox.scrcpyviewer.ui.settings

/** Which Settings category is selected and what is typed in the search field. */
data class SettingsNav(
    val selected: SettingsCategoryId = SettingsCategoryId.Appearance,
    /** As typed, untrimmed; a blank query is not a search. */
    val query: String = "",
    /** Category to return to when the search is cleared; null when the user picked one mid-search. */
    val restoreTo: SettingsCategoryId? = null,
) {
    private val searching get() = query.isNotBlank()

    fun withQuery(query: String): SettingsNav {
        val next = copy(query = query)
        return when {
            !searching && next.searching -> next.copy(restoreTo = selected)
            searching && !next.searching -> next.copy(selected = restoreTo ?: selected, restoreTo = null)
            else -> next
        }
    }

    fun select(id: SettingsCategoryId) = copy(selected = id, restoreTo = null)

    /** [selected] if it is in [visible], else the first of [visible]; null when [visible] is empty. */
    fun shown(visible: List<SettingsCategoryId>): SettingsCategoryId? = if (selected in visible) selected else visible.firstOrNull()
}
