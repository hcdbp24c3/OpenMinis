package com.openminis.app.ui.settings

import com.openminis.app.data.repository.ProviderRepository
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * [T-android-hidden-models-group] (#462 / #442) The provider detail screen
 * lists only visible models; hidden ones sit in a collapsible "Hidden models
 * (N)" group at the bottom, where each can be shown again or deleted. Delete
 * is offered for every model, not only custom ones, as on iOS.
 *
 * Source guard: a Compose test would need the whole SettingsScaffold and a
 * ProviderRepository for what is list selection plus one menu condition.
 */
class ProviderDetailHiddenModelsTest {

    private val src = File("src/main/java/com/openminis/app/ui/settings/ProviderDetailScreen.kt").readText()
    private val models = src.substringAfter("// ─── Models ───").substringBefore("MinisOutlinedButton(")

    // (fork) The fork lists a provider's models in ManageProviderModelsSheet
    // (search, eye toggle, long-press menu) instead of upstream's inline list
    // with a collapsible "Hidden models (N)" group. The three guards below
    // assert the same behaviour where the fork implements it.
    private val sheet = File("src/main/java/com/openminis/app/ui/settings/ManageProviderModelsSheet.kt").readText()

    @Test
    fun `the models row opens the sheet and hidden entries stay listed, dimmed, with a show toggle`() {
        assertTrue(models.contains("onClick = { showManageModelsSheet = true }"))
        assertFalse("no inline row for every entry", models.contains("entries.forEachIndexed"))
        assertTrue(src.contains("ManageProviderModelsSheet("))
        assertTrue(sheet.contains("if (entry.isHidden) Modifier.alpha(0.45f) else Modifier"))
        assertTrue(sheet.contains("if (entry.isHidden) Icons.Default.VisibilityOff else Icons.Default.Visibility"))
    }

    @Test
    fun `delete is offered for every model and API models get the come-back warning`() {
        assertFalse("delete is no longer custom-only", src.contains("if (entry.isCustom) {"))
        assertFalse("delete is no longer custom-only", sheet.contains("if (entry.isCustom) {"))
        assertTrue(Regex("""onClick = \{\s*onMenuEntryIdChange\(null\)\s*onDeleteRequest\(entry\)""").containsMatchIn(sheet))
        assertTrue(sheet.contains("onDeleteRequest = { entryToDelete = it }"))
        assertTrue(sheet.contains("if (e.isCustom) R.string.provider_detail_delete_model_confirm"))
        assertTrue(sheet.contains("else R.string.provider_detail_delete_api_model_confirm"))
        assertTrue(sheet.contains("providerRepository.removeEntry(e.id)"))
    }

    @Test
    fun `unhiding writes straight through and the absence grace is unchanged`() {
        assertTrue(sheet.contains("providerRepository.updateEntryAsync(target.copy(isHidden = newHidden))"))
        assertEquals(7L * 24 * 60 * 60 * 1000, ProviderRepository.MODEL_ABSENCE_GRACE_MS)
        val repo = File("src/main/java/com/openminis/app/data/repository/ProviderRepository.kt").readText()
        val remove = repo.substringAfter("fun removeEntry(entryId: String)").substringBefore("// --- Model Group management")
        assertFalse("removeEntry deletes any entry", remove.contains("isCustom"))
    }

    @Test
    fun `the new strings exist in every locale folder and no existing strings xml carries them`() {
        val folders = File("src/main/res").listFiles()!!.filter { File(it, "strings.xml").exists() }
        assertTrue(folders.size >= 23)
        folders.forEach { d ->
            val f = File(d, "strings_hidden_models.xml")
            assertTrue("missing ${f.path}", f.exists())
            val text = f.readText()
            assertTrue(text.contains("name=\"provider_detail_hidden_models_group\">"))
            assertTrue(text.contains("%1\$d"))
            assertTrue(text.contains("name=\"provider_detail_delete_api_model_confirm\">"))
            assertTrue(text.contains("%1\$s"))
            assertFalse(File(d, "strings.xml").readText().contains("provider_detail_hidden_models_group"))
        }
    }
}
