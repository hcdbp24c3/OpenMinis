package com.openminis.app.ui.chat

import java.io.File
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * [T-android-recentlyfailed-init-order] Every property `loadSession()` reads
 * synchronously must be declared ABOVE `init { … }`.
 *
 * Kotlin initialises a class body top to bottom, and `init { loadSession() }`
 * launches on `Dispatchers.Main.immediate`, which runs synchronously up to the
 * first suspension point. That stretch reaches `resolveProviderFromGroup`. A
 * property declared BELOW `init` has not run its initialiser by then, so the
 * read yields the JVM default — `null` for a reference type — and
 * `it.id !in recentlyFailedEntryIds` throws
 *
 *   NullPointerException: Attempt to invoke interface method
 *   'boolean java.util.Set.contains(java.lang.Object)' on a null object reference
 *
 * inside the constructor. The ViewModel never finishes building, so the crash
 * repeats on every launch that opens a session bound to a model group — which
 * is what made it a crash LOOP rather than a one-off: nothing bad is persisted,
 * the session simply keeps reopening and keeps re-entering the same
 * construction path. Shipped in 1.14(26); six field reports on 2026-09-13
 * (Xiaomi 23046RP50C / Android 15, HUAWEI MNA-AL00 / Android 12), all with
 * byte-identical top frames.
 *
 * The defect is invisible in review — the declaration reads perfectly fine
 * where it is, and the compiler is silent because the type is non-null on
 * paper. Only the ORDER is wrong. So the guard is a source scan: it is the one
 * check that can see what a runtime test of a correctly-ordered file cannot.
 *
 * The scan itself lives in [ChatViewModelInitOrderScan]; the synthetic-source
 * tests at the bottom pin its rules, because the first version matched a name
 * anywhere in the load path and reported three non-reads (`Log.w(…`, and prose
 * inside a string literal and a comment) instead of the real thing.
 */
class ViewModelInitOrderTest {

    private val loadPathFunctions = listOf("loadSession", "resolveProviderFromGroup", "applyGroupSessionDefaults")

    private fun source(): List<String> {
        val f = File("src/main/java/com/openminis/app/ui/chat/ChatViewModel.kt")
        assertTrue("ChatViewModel.kt not found (cwd=${File(".").absolutePath})", f.isFile)
        return f.readLines()
    }

    @Test
    fun `recentlyFailedEntryIds is declared before init`() {
        // The exact field that crashed: read by resolveProviderFromGroup via
        // `it.id !in recentlyFailedEntryIds`, on the synchronous stretch of
        // loadSession().
        val lines = source()
        val decl = ChatViewModelInitOrderScan.declarationLine(lines, "recentlyFailedEntryIds")
        val init = ChatViewModelInitOrderScan.initLine(lines)
        assertTrue("recentlyFailedEntryIds not found in ChatViewModel", decl > 0)
        assertTrue("no class-body `init {` found in ChatViewModel", init > 0)
        assertTrue(
            "recentlyFailedEntryIds is declared at line $decl, BELOW `init` at line $init — " +
                "loadSession() reads it synchronously, so it will be null and the " +
                "ViewModel constructor will throw NPE on every launch",
            decl < init,
        )
    }

    @Test
    fun `every property the synchronous load path reads is declared before init`() {
        // Generalised: the same trap applies to anything else loadSession() and
        // the functions it calls before suspending happen to touch. Keeping
        // this list derived from the source rather than hard-coded means a new
        // field added to that path is covered without anyone remembering to.
        val lines = source()
        val init = ChatViewModelInitOrderScan.initLine(lines)
        assertTrue("no class-body `init {` found in ChatViewModel", init > 0)

        // The scan no longer rejects a short scope on its own (synthetic sources in the
        // tests below are short by design), so assert here that the extraction found the
        // real load path: an empty scope would silently report "no offenders".
        val scope = ChatViewModelInitOrderScan.readPathScope(lines.joinToString("\n"), loadPathFunctions)
        assertTrue("could not extract the load path from the source", scope.length > 200)

        val offenders = ChatViewModelInitOrderScan.offenders(lines, loadPathFunctions)

        assertTrue(
            "these properties are declared BELOW `init` (line $init) yet are read on " +
                "loadSession()'s synchronous path — each will be null/default when the " +
                "constructor runs: $offenders",
            offenders.isEmpty(),
        )
    }

    // ── the scan's own rules, on synthetic sources ───────────────────────────
    //
    // Without these, "the scan finds nothing" is indistinguishable from "the scan
    // is blind" — and the three false positives it used to report are exactly the
    // shapes these tests pin.

    /** A synthetic file with [propertyLines] (already 4-space indented) below `init`. */
    private fun synth(propertyLines: String, loadPath: String): List<String> = buildString {
        appendLine("class T {")
        appendLine("    private val first = 1")
        appendLine("    init {")
        appendLine("        loadSession()")
        appendLine("    }")
        appendLine(propertyLines)
        appendLine("}")
        appendLine("fun loadSession() {")
        appendLine(loadPath)
        appendLine("}")
    }.lines()

    private fun offendersOf(propertyLines: String, loadPath: String): List<String> =
        ChatViewModelInitOrderScan.offenders(synth(propertyLines, loadPath), listOf("loadSession"))

    @Test
    fun `a bare read below init is reported`() {
        val offenders = offendersOf(
            propertyLines = "    private val needed = setOf(1)",
            loadPath = "foo(needed)",
        )
        assertEquals(listOf("needed (line 6)"), offenders)
    }

    @Test
    fun `a member access, a string and a comment are not reads`() {
        // The three non-reads that made the old scan unusable: Log.w(TAG, …), a
        // "threshold=…" log message, and the English word "line" in a comment.
        val offenders = offendersOf(
            propertyLines = """
                private val w = 1
                private val threshold = 2
                private val line = 3
            """.trimIndent().lines().joinToString("\n") { "    $it" },
            loadPath = """
                Log.w(TAG, "count=12 threshold=${'$'}{other}")
                // fire a line for a threshold crossed hours ago
            """.trimIndent(),
        )
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `an interpolation inside a string is a read`() {
        // `"${'$'}{needed}"` is a genuine read of the field; dropping the whole literal
        // would hide it.
        val offenders = offendersOf(
            propertyLines = "    private val needed = setOf(1)",
            loadPath = """Log.d(TAG, "ids=${'$'}{needed}")""",
        )
        assertEquals(listOf("needed (line 6)"), offenders)
    }

    @Test
    fun `a name that is also a local in the scope is skipped`() {
        // Conservative by design: a same-named local makes every occurrence
        // ambiguous, and a missed offence is the better failure for a guard whose
        // false alarms are what make it deletable.
        val offenders = offendersOf(
            propertyLines = "    private val needed = setOf(1)",
            loadPath = "val needed = compute()\nfoo(needed)",
        )
        assertEquals(emptyList<String>(), offenders)
    }

    @Test
    fun `a property declared above init is never reported`() {
        val lines = buildString {
            appendLine("class T {")
            appendLine("    private val needed = setOf(1)")
            appendLine("    init {")
            appendLine("        loadSession()")
            appendLine("    }")
            appendLine("}")
            appendLine("fun loadSession() {")
            appendLine("    foo(needed)")
            appendLine("}")
        }.lines()
        assertEquals(emptyList<String>(), ChatViewModelInitOrderScan.offenders(lines, listOf("loadSession")))
    }

    @Test
    fun `lazy properties are exempt because they resolve on first read`() {
        // The real file's head does exactly this, and it is correct: a `by lazy` field
        // resolves when first read, so its position in the class body is irrelevant.
        val lines = synth("    private val needed by lazy { setOf(1) }", "foo(needed)")
        assertEquals(emptyList<String>(), ChatViewModelInitOrderScan.offenders(lines, listOf("loadSession")))
    }
}
