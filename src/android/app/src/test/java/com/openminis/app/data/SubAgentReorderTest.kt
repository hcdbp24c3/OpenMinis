package com.openminis.app.data

import com.openminis.app.data.model.SubAgentDefinition
import com.openminis.app.data.model.SubAgentRoster
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * [T-android-subagent-settings-parity] The roster's order is the order the
 * model is shown its options in, so reordering has to survive a normalize()
 * round trip — the settings screen writes an id order, and the loader is what
 * decides what the roster actually becomes.
 *
 * The interesting case is the built-in: normalize() pins it to index 0
 * regardless of stored sortOrder, so a UI that let it move would appear to work
 * and then silently revert on the next read. These tests pin that contract from
 * the caller's side rather than trusting the screen's enabled-state.
 */
class SubAgentReorderTest {

    private fun custom(name: String, order: Int) = SubAgentDefinition(
        id = "id-$name", name = name, description = "d", sortOrder = order,
    )

    private fun roster() = SubAgentRoster.normalize(
        listOf(
            SubAgentDefinition.makeBuiltIn(),
            custom("alpha", 1),
            custom("beta", 2),
            custom("gamma", 3),
        )
    )

    /** The names of every built-in, in roster order. */
    private val builtInNames = SubAgentDefinition.makeBuiltIns().map { it.name }

    /** Mirrors SubAgentsScreen.move(): reorder ids, then let the loader rule. */
    private fun applyMove(list: List<SubAgentDefinition>, from: Int, to: Int): List<String> {
        if (from !in list.indices || to !in list.indices) return list.map { it.name }
        // The guard is the first CUSTOM index, not a hard-coded 0: with several
        // built-ins pinned to the front, index 0 alone would let a custom row be
        // dropped inside their block.
        val firstCustom = list.indexOfFirst { !it.isBuiltIn }.let { if (it < 0) list.size else it }
        if (from < firstCustom || to < firstCustom) return list.map { it.name }
        val ids = list.map { it.id }.toMutableList()
        ids.add(to, ids.removeAt(from))
        val byId = list.associateBy { it.id }
        val reordered = ids.mapNotNull { byId[it] }
            .mapIndexed { i, d -> d.copy(sortOrder = i) }
        return SubAgentRoster.normalize(reordered).map { it.name }
    }

    /** Index of the first custom row in a normalized roster. */
    private fun firstCustom(list: List<SubAgentDefinition>) =
        list.indexOfFirst { !it.isBuiltIn }.let { if (it < 0) list.size else it }

    @Test
    fun `moving a custom agent up reorders it`() {
        val start = roster()
        val c = firstCustom(start)
        assertEquals(
            builtInNames + listOf("beta", "alpha", "gamma"),
            applyMove(start, from = c + 1, to = c),
        )
    }

    @Test
    fun `moving a custom agent down reorders it`() {
        val start = roster()
        val c = firstCustom(start)
        assertEquals(
            builtInNames + listOf("beta", "alpha", "gamma"),
            applyMove(start, from = c, to = c + 1),
        )
    }

    @Test
    fun `a built-in cannot be moved off the front`() {
        // Guarded in move() AND re-pinned by normalize(): two independent
        // reasons this cannot happen, because a roster whose first entries are
        // not the built-ins would change what the model is told they are for.
        assertEquals(
            builtInNames + listOf("alpha", "beta", "gamma"),
            applyMove(roster(), from = 0, to = 3),
        )
    }

    @Test
    fun `nothing can displace the built-ins from the front`() {
        assertEquals(
            builtInNames + listOf("alpha", "beta", "gamma"),
            applyMove(roster(), from = roster().lastIndex, to = 0),
        )
    }

    @Test
    fun `a custom row cannot be moved into the built-in block`() {
        // The move is refused rather than silently undone by normalize's re-pin:
        // the list comes back exactly as it went in.
        val start = roster()
        val c = firstCustom(start)
        assertEquals(start.map { it.name }, applyMove(start, from = c, to = c - 1))
        assertEquals(start.map { it.name }, applyMove(start, from = c, to = 0))
    }

    @Test
    fun `sortOrder stays dense after a move`() {
        val start = roster()
        val c = firstCustom(start)
        val ids = start.map { it.id }.toMutableList()
        ids.add(c, ids.removeAt(c + 1))
        val byId = start.associateBy { it.id }
        val out = SubAgentRoster.normalize(
            ids.mapNotNull { byId[it] }.mapIndexed { i, d -> d.copy(sortOrder = i) }
        )
        // A gap here would make the NEXT move compute the wrong destination.
        assertEquals((0 until start.size).toList(), out.map { it.sortOrder })
    }
}
