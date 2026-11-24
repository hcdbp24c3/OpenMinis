package com.openminis.app.ui.settings

import com.openminis.app.ProductionSources
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-manage-sheet-system-back] System back on the provider's model list sheet
 * closes it the same way its Close button does, and the sheet still reopens
 * after returning from the model-edit screen ([T-fix-manage-sheet-reopen]).
 */
class ManageSheetBackTest {
    private val sheet: String = ProductionSources.read("ui/settings/ManageProviderModelsSheet.kt")
    private val host: String = ProductionSources.read("ui/settings/ProviderDetailScreen.kt")

    @Test
    fun systemBackInsideTheSheetCallsOnDismissDirectly() {
        val start = sheet.indexOf("ModalBottomSheet(")
        assertTrue(start >= 0)
        val handler = sheet.indexOf("BackHandler(enabled = menuEntryId == null && entryToDelete == null) { onDismiss() }", start)
        assertTrue("back handler sits inside the sheet content", handler > start)
    }

    @Test
    fun hostClosesTheSheetBeforeLeavingTheScreen() {
        val shown = host.indexOf("if (showManageModelsSheet) {")
        assertTrue(shown >= 0)
        val handler = host.indexOf("BackHandler { showManageModelsSheet = false }", shown)
        val call = host.indexOf("ManageProviderModelsSheet(", shown)
        assertTrue(handler in (shown + 1) until call)
    }

    @Test
    fun reopenAfterModelEditIsKept() {
        assertTrue(host.contains("var showManageModelsSheet by rememberSaveable { mutableStateOf(false) }"))
        assertTrue(sheet.contains("onClick = { onModelEntryClick(entry.id) },"))
    }
}
