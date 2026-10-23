package com.programmersbox.scrcpyviewer.presets

import com.programmersbox.scrcpyviewer.options.OptionValues
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class ImportPlanTest {
    private fun p(id: String, name: String, vararg o: String) =
        Preset(id, name, "", OptionValues(o.associateWith { null }))

    private val existing = listOf(p("e1", "Fast", "no-audio"))

    @Test
    fun `plan flags name conflicts`() {
        val items = ImportPlan.plan(existing, listOf(p("n1", "Fast"), p("n2", "Other")))
        assertEquals(existing[0], items[0].conflictsWith)
        assertNull(items[1].conflictsWith)
    }

    @Test
    fun `apply Replace keeps id of existing`() {
        val items = ImportPlan.plan(existing, listOf(p("n1", "Fast", "no-video"))).map { it.copy(choice = ConflictChoice.Replace) }
        val out = ImportPlan.apply(existing, items)
        assertEquals(1, out.size)
        assertEquals("e1", out[0].id)
        assertEquals(setOf("no-video"), out[0].options.entries.keys)
    }

    @Test
    fun `apply KeepBoth renames to (2)`() {
        val items = ImportPlan.plan(existing, listOf(p("n1", "Fast"))).map { it.copy(choice = ConflictChoice.KeepBoth) }
        assertEquals(listOf("Fast", "Fast (2)"), ImportPlan.apply(existing, items).map { it.name })
        val twice = ImportPlan.apply(ImportPlan.apply(existing, items), items)
        assertEquals(listOf("Fast", "Fast (2)", "Fast (3)"), twice.map { it.name })
    }

    @Test
    fun `apply Skip leaves existing untouched`() {
        val items = ImportPlan.plan(existing, listOf(p("n1", "Fast"))).map { it.copy(choice = ConflictChoice.Skip) }
        assertEquals(existing, ImportPlan.apply(existing, items))
    }

    @Test
    fun `apply adds non conflicting and honours include false`() {
        val items = ImportPlan.plan(existing, listOf(p("n2", "Other"), p("n3", "Off"))).let {
            listOf(it[0], it[1].copy(include = false))
        }
        assertEquals(listOf("Fast", "Other"), ImportPlan.apply(existing, items).map { it.name })
    }
}
