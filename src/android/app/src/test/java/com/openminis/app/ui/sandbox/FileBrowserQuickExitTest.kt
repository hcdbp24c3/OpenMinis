package com.openminis.app.ui.sandbox

import com.openminis.app.ProductionSources
import org.junit.Assert.assertTrue
import org.junit.Test

/** [T-filebrowser-quick-exit] A one-tap close from any depth; per-folder back is unchanged. */
class FileBrowserQuickExitTest {
    private val src: String = ProductionSources.read("ui/sandbox/FileBrowserScreen.kt")

    @Test
    fun closeActionLeavesTheBrowserInOneTap() {
        val actions = src.indexOf("actions = {")
        assertTrue(actions >= 0)
        val close = src.indexOf("IconButton(onClick = onBack) {", actions)
        assertTrue(close > actions)
        assertTrue(src.indexOf("if (state.canGoBack) {", actions) in (actions + 1) until close)
    }

    @Test
    fun backStillPopsOneFolderAtATime() {
        assertTrue(src.contains("if (!state.canGoBack || !viewModel.goBack()) onBack()"))
        assertTrue(src.contains("BackHandler(enabled = true, onBack = handleBack)"))
    }
}
