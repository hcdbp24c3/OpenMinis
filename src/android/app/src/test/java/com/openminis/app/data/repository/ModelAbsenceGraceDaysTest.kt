package com.openminis.app.data.repository

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/** [T-model-absence-grace-days] The user-chosen grace window, as a pure decision. */
class ModelAbsenceGraceDaysTest {
    private val day = 24L * 60 * 60 * 1000
    private val t0 = 1_800_000_000_000L

    @Test
    fun `default is upstream's 7 days`() {
        assertEquals(7, ModelAbsenceGrace.DEFAULT_DAYS)
        assertEquals(ProviderRepository.MODEL_ABSENCE_GRACE_MS, ModelAbsenceGrace.graceMs(ModelAbsenceGrace.DEFAULT_DAYS))
    }

    @Test
    fun `first miss is kept for any window above zero`() {
        for (d in listOf(1, 7, 30, 90)) {
            assertFalse("first miss kept at $d days", ModelAbsenceGrace.shouldDrop(null, t0, d))
        }
    }

    @Test
    fun `zero days drops on the first miss and on later ones`() {
        assertTrue(ModelAbsenceGrace.shouldDrop(null, t0, 0))
        assertTrue(ModelAbsenceGrace.shouldDrop(t0, t0, 0))
        assertTrue(ModelAbsenceGrace.shouldDrop(t0 - day, t0, 0))
    }

    @Test
    fun `default window matches the fixed 7 day rule at the boundary`() {
        val grace = ProviderRepository.MODEL_ABSENCE_GRACE_MS
        assertFalse(ModelAbsenceGrace.shouldDrop(t0, t0 + grace - 60_000, 7))
        assertFalse("exactly at the deadline is still kept", ModelAbsenceGrace.shouldDrop(t0, t0 + grace, 7))
        assertTrue(ModelAbsenceGrace.shouldDrop(t0, t0 + grace + 60_000, 7))
    }

    @Test
    fun `a custom window moves the deadline`() {
        assertFalse(ModelAbsenceGrace.shouldDrop(t0, t0 + 2 * day, 3))
        assertTrue(ModelAbsenceGrace.shouldDrop(t0, t0 + 3 * day + 1, 3))
        assertFalse(ModelAbsenceGrace.shouldDrop(t0, t0 + 20 * day, 30))
        assertTrue(ModelAbsenceGrace.shouldDrop(t0, t0 + 31 * day, 30))
    }

    @Test
    fun `days are clamped to 0 to 90`() {
        assertEquals(0, ModelAbsenceGrace.clampDays(-5))
        assertEquals(90, ModelAbsenceGrace.clampDays(365))
        assertEquals(90 * day, ModelAbsenceGrace.graceMs(1000))
        // An out-of-range stored value behaves like the nearest bound.
        assertTrue(ModelAbsenceGrace.shouldDrop(null, t0, -1))
        assertFalse(ModelAbsenceGrace.shouldDrop(t0, t0 + 90 * day, 1000))
        assertTrue(ModelAbsenceGrace.shouldDrop(t0, t0 + 90 * day + 1, 1000))
    }

    @Test
    fun `typed input is parsed leniently and rejected when not a number`() {
        assertEquals(0, ModelAbsenceGrace.parseDays("0"))
        assertEquals(14, ModelAbsenceGrace.parseDays(" 14 "))
        assertEquals(90, ModelAbsenceGrace.parseDays("500"))
        assertNull(ModelAbsenceGrace.parseDays(""))
        assertNull(ModelAbsenceGrace.parseDays("-3"))
        assertNull(ModelAbsenceGrace.parseDays("7d"))
        assertNull(ModelAbsenceGrace.parseDays("99999999999"))
    }

    @Test
    fun `labels read naturally`() {
        assertEquals("Remove right away", ModelAbsenceGrace.label(0))
        assertEquals("1 day", ModelAbsenceGrace.label(1))
        assertEquals("7 days", ModelAbsenceGrace.label(7))
    }

    @Test
    fun `replaceEntries asks this rule and still shields custom and voice entries`() {
        val repo = File("src/main/java/com/openminis/app/data/repository/ProviderRepository.kt").readText()
        val block = repo.substringAfter("// [T-android-model-absence-grace] Catalog entries")
            .substringBefore("val entriesToRemove")
        assertTrue(block.contains("ModelAbsenceGrace.shouldDrop(entry.absentSince, nowMs, graceDays)"))
        assertTrue(block.contains("val graceDays = ModelAbsenceGrace.days(context)"))
        assertTrue(block.contains("!e.isCustom &&"))
        assertTrue(block.contains("e.baseModel.id !in templateVoiceModelById"))
        assertTrue("constant stays as the default", repo.contains("internal const val MODEL_ABSENCE_GRACE_MS = 7L * 24 * 60 * 60 * 1000"))
    }
}
