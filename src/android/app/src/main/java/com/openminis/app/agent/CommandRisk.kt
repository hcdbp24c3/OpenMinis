package com.openminis.app.agent

/**
 * [T-coding-m2-approval] Sorts a shell command into one of three buckets so the
 * approval gate can decide whether to stop and ask.
 *
 * Pure string work, no Android types — unit-tested on the JVM.
 *
 * This is a tripwire, not a sandbox. It reads the command the way a careful
 * person would skim it: it does not expand variables, follow scripts or resolve
 * aliases, so a determined command can slip past. Its job is to catch the
 * mistakes a model actually makes (a recursive delete aimed at the wrong place,
 * a forced push, a piped installer) and to keep plainly harmless commands from
 * nagging.
 *
 * Deliberately NOT built on ShellTimeoutPolicy.quickPrefixes: that list answers
 * "is this fast", and contains rm / mv / cp / chmod.
 */
object CommandRisk {

    enum class Level { READ_ONLY, NORMAL, DANGEROUS }

    data class Verdict(val level: Level, val reason: String? = null)

    private val READ_ONLY_COMMANDS = setOf(
        "ls", "cat", "head", "tail", "rg", "grep", "egrep", "fgrep", "pwd", "wc",
        "stat", "file", "which", "whoami", "id", "uname", "date", "env",
        "printenv", "du", "df", "tree", "sort", "uniq", "cut", "tr", "nl",
        "basename", "dirname", "realpath", "readlink", "true", "false", "test",
        "[", "cd", "echo", "printf", "diff", "cmp", "md5sum", "sha1sum",
        "sha256sum", "hexdump", "xxd", "strings", "ps", "free", "uptime",
        "type", "command", "less", "more",
    )

    private val READ_ONLY_GIT = setOf(
        "status", "diff", "log", "show", "blame", "rev-parse", "ls-files",
        "ls-tree", "describe", "shortlog", "grep", "cat-file", "reflog",
        "version", "--version", "help",
    )

    private val PROTECTED_DIRS = setOf(
        "/", "/root", "/home", "/etc", "/usr", "/var", "/bin", "/sbin", "/lib",
        "/opt", "/var/minis", "/var/minis/mounts", "/var/minis/shared",
        "/var/minis/workspace", "/var/minis/skills",
    )

    private const val NO_QUOTE = '\u0000'

    private val SHELLS = setOf("sh", "bash", "ash", "zsh", "dash")

    fun classify(command: String): Verdict {
        val segments = splitSegments(command)
        if (segments.isEmpty()) return Verdict(Level.NORMAL)

        for (segment in segments) {
            dangerReason(segment)?.let { return Verdict(Level.DANGEROUS, it) }
        }
        pipedInstallerReason(command)?.let { return Verdict(Level.DANGEROUS, it) }

        // Redirection and command substitution can write or run anything, so a
        // command that uses either is never called read-only.
        if (command.contains('>') || command.contains('`') || command.contains("$(")) {
            return Verdict(Level.NORMAL)
        }
        return if (segments.all { isReadOnly(it) }) Verdict(Level.READ_ONLY) else Verdict(Level.NORMAL)
    }

    // ------------------------------------------------------------- tokenizing

    /**
     * Split on `;`, `&&`, `||`, `|`, `&` and newlines that sit outside quotes,
     * then split each piece into words. Quotes are removed; nothing is expanded.
     */
    internal fun splitSegments(command: String): List<List<String>> {
        val segments = ArrayList<List<String>>()
        var words = ArrayList<String>()
        val word = StringBuilder()
        var hasWord = false
        var quote = NO_QUOTE
        var i = 0

        fun endWord() {
            if (hasWord) words.add(word.toString())
            word.setLength(0)
            hasWord = false
        }

        fun endSegment() {
            endWord()
            if (words.isNotEmpty()) segments.add(words)
            words = ArrayList()
        }

        while (i < command.length) {
            val c = command[i]
            if (quote != NO_QUOTE) {
                if (c == quote) {
                    quote = NO_QUOTE
                } else if (c == '\\' && quote == '"' && i + 1 < command.length) {
                    i++
                    word.append(command[i])
                } else {
                    word.append(c)
                }
            } else if (c == '\'' || c == '"') {
                quote = c
                hasWord = true
            } else if (c == '\\' && i + 1 < command.length) {
                i++
                word.append(command[i])
                hasWord = true
            } else if (c == ';' || c == '\n' || c == '|' || c == '&') {
                endSegment()
            } else if (c == ' ' || c == '\t' || c == '\r') {
                endWord()
            } else {
                word.append(c)
                hasWord = true
            }
            i++
        }
        endSegment()
        return segments
    }

    /** Drop leading `VAR=value` assignments and wrappers such as sudo / env. */
    private fun stripPrefix(words: List<String>): List<String> {
        var i = 0
        while (i < words.size) {
            val w = words[i]
            val isAssignment = w.indexOf('=') > 0 && w[0] != '-' &&
                w.substring(0, w.indexOf('=')).all { it.isLetterOrDigit() || it == '_' }
            if (isAssignment || w == "sudo" || w == "env" || w == "nohup" || w == "time" || w == "exec") {
                i++
            } else {
                break
            }
        }
        return words.subList(i, words.size)
    }

    private fun name(word: String): String = word.substringAfterLast('/')

    private fun hasShortFlag(args: List<String>, flag: Char): Boolean =
        args.any { it.length >= 2 && it[0] == '-' && it[1] != '-' && it.indexOf(flag) > 0 }

    // ---------------------------------------------------------------- danger

    private fun dangerReason(rawWords: List<String>): String? {
        val words = stripPrefix(rawWords)
        if (words.isEmpty()) return null
        val cmd = name(words[0])
        val args = words.drop(1)

        when (cmd) {
            "rm" -> {
                val recursive = hasShortFlag(args, 'r') || hasShortFlag(args, 'R') ||
                    args.contains("--recursive")
                if (recursive) {
                    val target = args.firstOrNull { !it.startsWith("-") && isBroadTarget(it) }
                    if (target != null) return "recursive delete of $target"
                }
            }
            "git" -> gitDangerReason(args)?.let { return it }
            "dd" -> if (args.any { it.startsWith("of=/dev/") }) return "dd writing to a device"
            "chmod", "chown" -> {
                val recursive = hasShortFlag(args, 'R') || args.contains("--recursive")
                val target = args.firstOrNull { !it.startsWith("-") && isBroadTarget(it) }
                if (recursive && target != null) return "recursive $cmd of $target"
            }
            "find" -> if (args.contains("-delete")) return "find -delete"
            "shred", "wipefs" -> return cmd
        }
        if (cmd.startsWith("mkfs")) return cmd
        return null
    }

    private fun gitDangerReason(args: List<String>): String? {
        // Skip `-C dir` / `-c key=value` so the subcommand is found.
        var i = 0
        while (i < args.size && args[i].startsWith("-")) {
            i += if ((args[i] == "-C" || args[i] == "-c") && i + 1 < args.size) 2 else 1
        }
        if (i >= args.size) return null
        val sub = args[i]
        val rest = args.drop(i + 1)
        return when (sub) {
            "push" -> if (rest.any { it == "-f" || it == "--force" || it.startsWith("--force-with-lease") || it == "--mirror" || it == "--delete" || (it.startsWith("+") && it.length > 1) }) {
                "git push that rewrites or deletes remote history"
            } else {
                null
            }
            "reset" -> if (rest.contains("--hard")) "git reset --hard" else null
            "clean" -> if (rest.any { it.startsWith("-") && !it.startsWith("--") && it.contains('f') } && !rest.any { it == "-n" || it == "--dry-run" || (it.startsWith("-") && !it.startsWith("--") && it.contains('n')) }) {
                "git clean (deletes untracked files)"
            } else {
                null
            }
            "checkout", "restore" -> if (rest.contains(".") || rest.contains(":/")) "git $sub that discards local changes" else null
            "stash" -> if (rest.firstOrNull() == "drop" || rest.firstOrNull() == "clear") "git stash ${rest.first()}" else null
            "branch" -> if (rest.contains("-D")) "git branch -D" else null
            "filter-branch", "filter-repo" -> "git $sub"
            else -> null
        }
    }

    /** A delete target wide enough that a typo is expensive. */
    internal fun isBroadTarget(raw: String): Boolean {
        if (raw.isEmpty()) return false
        if (raw == "*" || raw == "." || raw == ".." || raw == "~" || raw == "\$HOME" || raw == "\${HOME}") return true
        var path = raw
        val wildcardAll = path.endsWith("/*")
        if (wildcardAll) path = path.dropLast(2)
        while (path.length > 1 && path.endsWith("/")) path = path.dropLast(1)
        if (path.isEmpty()) return true                       // "/*" or "/"
        if (path == "." || path == ".." || path == "~" || path == "\$HOME" || path == "\${HOME}") return true
        if (path in PROTECTED_DIRS) return true
        // One level under a mount or the shared area is a whole project.
        for (root in listOf("/var/minis/mounts/", "/var/minis/shared/")) {
            if (path.startsWith(root) && !path.substring(root.length).contains('/')) return true
        }
        return false
    }

    private val PIPED_INSTALLER = Regex(
        """\b(curl|wget)\b[^|;&\n]*\|\s*(sudo\s+)?(sh|bash|ash|zsh|dash)\b""",
    )

    private fun pipedInstallerReason(command: String): String? =
        if (PIPED_INSTALLER.containsMatchIn(command)) "download piped straight into a shell" else null

    // -------------------------------------------------------------- read-only

    private fun isReadOnly(rawWords: List<String>): Boolean {
        val words = stripPrefix(rawWords)
        if (words.isEmpty()) return true
        val cmd = name(words[0])
        val args = words.drop(1)
        if (cmd in SHELLS) return false
        if (cmd == "git") {
            var i = 0
            while (i < args.size && args[i].startsWith("-")) {
                if (args[i] == "--version" || args[i] == "--help") return true
                i += if ((args[i] == "-C" || args[i] == "-c") && i + 1 < args.size) 2 else 1
            }
            return i < args.size && args[i] in READ_ONLY_GIT
        }
        if (cmd == "find") {
            return args.none { it == "-delete" || it == "-exec" || it == "-execdir" || it == "-ok" || it == "-okdir" || it.startsWith("-fprint") || it == "-fls" }
        }
        return cmd in READ_ONLY_COMMANDS
    }
}
