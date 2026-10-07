package com.openminis.app.ui.chat

/**
 * [T-android-recentlyfailed-init-order] The source scan behind
 * [ViewModelInitOrderTest], as a pure function so it can be tested on synthetic
 * sources instead of only on the shipping file.
 *
 * The guard exists because Kotlin runs a class body top to bottom: `init { loadSession() }`
 * executes synchronously to the first suspension point, so a property declared BELOW
 * `init` still holds its JVM default there (null for a reference type) and the
 * constructor throws — the 1.14(26) crash loop. The order is invisible to the compiler
 * and to review, which is why a scan is the only check that sees it.
 *
 * A scan that misses the crash is useless, but one that cries wolf is worse: it hides
 * the real offence in noise and gets deleted. The first version matched a property NAME
 * anywhere in the load path, so it flagged `w` (from `Log.w(TAG, …)`), `threshold` and
 * `line` (both mentioned only inside a STRING LITERAL and a COMMENT) — a method name, a
 * log message and English prose, none of them reads. Three rules fix that:
 *
 *  1. comments and string literals are scrubbed first — except `${…}` and `$name`
 *     interpolations inside them, which ARE reads;
 *  2. a read must be bare: not a member access (`Log.w`), not a longer identifier;
 *  3. a name that is also declared as a local in the same scope is skipped — a
 *     conservative call: a genuine offence could hide behind a same-named local, and
 *     that is the better failure for a guard whose false alarms are the problem.
 */
internal object ChatViewModelInitOrderScan {

    /** Line number (1-based) of the class-body `init {`, or -1 when absent. */
    fun initLine(lines: List<String>): Int =
        lines.indexOfFirst { it.trimEnd() == "    init {" }.let { if (it < 0) -1 else it + 1 }

    /** Line number (1-based) of a class-body `val`/`var` declaration, or -1. */
    fun declarationLine(lines: List<String>, name: String): Int =
        lines.indexOfFirst {
            Regex("""^\s*(?:private |internal )?(?:val|var)\s+$name\b""").containsMatchIn(it)
        }.let { if (it < 0) -1 else it + 1 }

    /**
     * Concatenated bodies of the functions that run synchronously inside the
     * constructor: [loadSession] and the two helpers it reaches before suspending.
     */
    fun readPathScope(text: String, functions: List<String>): String = buildString {
        for (fn in functions) {
            val m = Regex("""\n\s*(?:private |internal )?(?:suspend )?fun $fn\(""").find(text) ?: continue
            var depth = 0
            var i = text.indexOf('{', m.range.first)
            if (i < 0) continue
            val start = i
            while (i < text.length) {
                if (text[i] == '{') depth++
                else if (text[i] == '}') {
                    depth--
                    if (depth == 0) break
                }
                i++
            }
            append(text.substring(start, minOf(i + 1, text.length)))
        }
    }

    /**
     * Class-body properties declared BELOW `init` with a plain initialiser that the
     * synchronous load path reads bare, as `name (line N)` entries.
     *
     * `by lazy` is exempt: it resolves on first read, not in declaration order, which is
     * exactly the hazard being tested for.
     */
    fun offenders(lines: List<String>, functions: List<String>): List<String> {
        val init = initLine(lines)
        if (init < 0) return emptyList()
        val scope = scrub(readPathScope(lines.joinToString("\n"), functions))

        // Two things separate a class-body property from a function-local here, and
        // neither works alone: a top-level function's body sits at the SAME depth 1 as a
        // class body (`warmUpBudget` declares `val w` / `val threshold` / `val line`
        // exactly there), and indentation is identical at four spaces. Depth within the
        // CLASS BODY is the rule: a member is depth 1, a local of one of its functions is
        // depth 2, and anything outside the class is not a member at all.
        val depth = depths(lines)
        val classBody = classBodyRange(lines)

        val out = mutableListOf<String>()
        for ((i, line) in lines.withIndex()) {
            val lineNo = i + 1
            if (lineNo <= init) continue
            if (depth[i] != 1) continue
            if (classBody != null && i !in classBody) continue
            val m = Regex("""^    (?:private |internal )?(?:val|var) (\w+)\s*(?::[^=]+)?""").find(line) ?: continue
            if ('=' !in line) continue
            if ("by lazy" in line) continue
            val name = m.groupValues[1]
            if (isBareRead(scope, name)) out.add("$name (line $lineNo)")
        }
        return out
    }

    /**
     * Zero-based line range of the class body — the lines after a top-level
     * `class … {` up to the line that closes it. Null when the file declares no class.
     */
    internal fun classBodyRange(lines: List<String>): IntRange? {
        val depth = depths(lines)
        for (i in lines.indices) {
            if (depth[i] != 0) continue
            val head = lines[i].trimStart()
            if (!Regex("""^(?:internal |private |open |abstract |sealed |data )*class \w+""").containsMatchIn(head)) continue
            var end = i
            while (end + 1 < lines.size && depth[end + 1] > 0) end++
            return (i + 1)..end
        }
        return null
    }

    /**
     * Brace depth at the START of each line, with comments and string-literal text
     * removed first — a `{` inside a log string or a KDoc example must not move the
     * class body. [scrub] keeps the newlines it removes, so scrubbed line N is still
     * source line N.
     */
    internal fun depths(lines: List<String>): IntArray {
        val scrubbed = scrub(lines.joinToString("\n")).split("\n")
        val out = IntArray(lines.size)
        var depth = 0
        for (i in lines.indices) {
            out[i] = depth
            for (c in scrubbed.getOrElse(i) { "" }) {
                if (c == '{') depth++
                else if (c == '}') depth--
            }
        }
        return out
    }

    /** True when [scope] reads [name] as a field of `this` rather than as anything else. */
    internal fun isBareRead(scope: String, name: String): Boolean {
        if (Regex("""\b(?:val|var|vararg|fun)\s+$name\b""").containsMatchIn(scope)) return false
        // A named parameter / lambda parameter of the same name reads as `name:`.
        if (Regex("""(?<![\w.])(?:\()?\s*$name\s*:\s*[\w<(]""").containsMatchIn(scope)) return false
        val read = Regex("""(?<![\w.$])${Regex.escape(name)}\b""")
        return read.containsMatchIn(scope)
    }

    /**
     * Strip what is not code: `//` and `/* */` comments, and the TEXT of string and
     * char literals. Interpolations inside a string are kept — `"${it.id} !in $set"`
     * reads both of those fields, and dropping the whole literal is what let three
     * log messages and comments masquerade as reads.
     */
    internal fun scrub(code: String): String {
        val out = StringBuilder(code.length)
        var i = 0
        while (i < code.length) {
            val c = code[i]
            when {
                c == '/' && i + 1 < code.length && code[i + 1] == '/' -> {
                    while (i < code.length && code[i] != '\n') i++
                    // Keep the newline: callers index the scrubbed text by source line.
                    if (i < code.length) {
                        out.append('\n')
                        i++
                    }
                }
                c == '/' && i + 1 < code.length && code[i + 1] == '*' -> {
                    i += 2
                    while (i + 1 < code.length && !(code[i] == '*' && code[i + 1] == '/')) {
                        if (code[i] == '\n') out.append('\n')
                        i++
                    }
                    i = minOf(i + 2, code.length)
                }
                c == '"' || c == '\'' -> i = copyInterpolations(code, i, out)
                else -> {
                    out.append(c)
                    i++
                }
            }
        }
        return out.toString()
    }

    /** Skip a string/char literal from [start], keeping only its `${…}`/`$name` parts. */
    private fun copyInterpolations(code: String, start: Int, out: StringBuilder): Int {
        val quote = code[start]
        val triple = quote == '"' && code.startsWith("\"\"\"", start)
        var i = start + if (triple) 3 else 1
        while (i < code.length) {
            if (!triple && code[i] == '\\') {
                i += 2
                continue
            }
            if (triple && code.startsWith("\"\"\"", i)) return i + 3
            if (!triple && code[i] == quote) return i + 1
            // Keep the line break so scrubbed line N is still source line N.
            if (code[i] == '\n') out.append('\n')
            if (code[i] == '$') {
                if (i + 1 < code.length && code[i + 1] == '{') {
                    val close = code.indexOf('}', i + 2)
                    if (close > 0) {
                        out.append(' ').append(code, i + 2, close).append(' ')
                        i = close + 1
                        continue
                    }
                } else {
                    val nameMatch = Regex("""^[A-Za-z_]\w*""").find(code.substring(i + 1))
                    if (nameMatch != null) {
                        out.append(' ').append(nameMatch.value).append(' ')
                        i += 1 + nameMatch.value.length
                        continue
                    }
                }
            }
            i++
        }
        return code.length
    }
}
