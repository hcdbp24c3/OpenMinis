package com.openminis.app.ui.chat

import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * [T-android-model-picker-debounce] Pins the debounce shared by the model
 * pickers: a burst of keystrokes must collapse into one filter pass.
 *
 * The picker's search/filter/rank logic itself moved to upstream's
 * `ui/components/ModelSearch` (covered by ModelSearchTest), and the
 * release-rank ordering to `ModelEntryRanking` (covered by
 * ModelEntryRankingTest), so nothing else lives here.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ModelPickerLogicTest {

    @Test
    fun `debounce waits for quiet before returning the query`() = runTest {
        var result: String? = null
        val job = launch { result = debounceSearchText("gpt", debounceMillis = 250) }
        advanceTimeBy(249)
        assertNull(result)
        advanceTimeBy(1)
        runCurrent()
        assertEquals("gpt", result)
        job.cancel()
    }
}
