package com.openminis.app.tools

import java.net.URI

/**
 * [T-web-m5] HTML → clean Markdown (or plain text) for `web_fetch`, with
 * main-content detection. No dependency: a tolerant tokenizer, a small tree
 * builder, a pruning pass, a body-selection pass and a renderer.
 *
 * Why not the regex pass in [WebFetchTool.htmlToMarkdown]: it converts the
 * WHOLE document, so a model asking for an article pays for the navigation
 * bar, the sidebar, the cookie banner and the footer first — on a phone-sized
 * budget those can be the entire first window — and tables, nested lists and
 * code-block languages degrade to loose text.
 *
 * Passes:
 *  1. tokenise + build a tree (real pages are not XML: unclosed `<p>`/`<li>`/
 *     `<td>`, stray end tags, attributes without quotes);
 *  2. hard prune: script/style/template/svg/iframe/form controls, comments,
 *     `hidden` / `aria-hidden` / `display:none` nodes;
 *  3. main mode only — chrome prune: `nav`/`aside`/`footer`, landmark roles,
 *     and containers whose class/id names them as furniture (sidebar, cookie,
 *     consent, share, related, …) unless they hold a large share of the text;
 *  4. main mode only — pick the body: the dominant `<article>`, else
 *     `<main>`/`role=main`, else walk down from `<body>` while one child still
 *     holds most of the non-link text;
 *  5. render headings, paragraphs, nested lists, block quotes, fenced code
 *     (with language), simple tables, rules, links (absolute) and images with
 *     alt text.
 *
 * The idea of this pipeline follows the dependency-free converters in the two
 * MIT-licensed reference plugins named in `openminis-m5/parts/web-notes.md`;
 * the code is written for this app.
 *
 * Pure: no network, no Android. Bounded: nesting depth is capped so a hostile
 * page cannot overflow the stack, and input is expected to be already capped
 * by the caller's body limit.
 */
object HtmlMarkdown {

    data class Page(
        /** `<title>` text, entity-decoded; empty when the page has none. */
        val title: String,
        val content: String,
        /** How the body was chosen: `article`, `main`, `density`, `body` or `full`. */
        val mode: String,
    )

    private const val MAX_DEPTH = 200
    private const val MAX_TABLE_COLUMNS = 12

    // ── tree ────────────────────────────────────────────────────────────────

    private sealed class Node

    private class Text(val text: String) : Node()

    private class El(val tag: String, val attrs: Map<String, String>) : Node() {
        val children: ArrayList<Node> = ArrayList()
        var textLen: Int = 0
        var linkLen: Int = 0
    }

    private val VOID: Set<String> = setOf(
        "area", "base", "br", "col", "embed", "hr", "img", "input", "link", "meta",
        "param", "source", "track", "wbr",
    )

    /** Content is skipped at tokenise time: it is never text a reader sees. */
    private val RAW_SKIP: Set<String> = setOf("script", "style", "noscript", "textarea")

    private val HARD_DROP: Set<String> = setOf(
        "script", "style", "noscript", "template", "svg", "canvas", "iframe", "object",
        "embed", "input", "button", "select", "textarea", "link", "meta", "head",
        "audio", "video", "map", "dialog",
    )

    private val CHROME_TAGS: Set<String> = setOf("nav", "aside", "footer", "menu")

    private val CHROME_ROLES: Set<String> = setOf(
        "navigation", "banner", "contentinfo", "complementary", "search", "dialog",
        "alertdialog", "menu", "menubar", "toolbar",
    )

    private val NOISE_TOKENS: Set<String> = setOf(
        "nav", "navbar", "navigation", "menu", "sidebar", "breadcrumb", "breadcrumbs",
        "footer", "cookie", "cookies", "consent", "gdpr", "banner", "advert",
        "advertisement", "ads", "ad", "social", "share", "sharing", "comments",
        "related", "recommended", "newsletter", "subscribe", "popup", "modal",
        "promo", "sponsor", "sponsored", "paywall", "skip",
    )

    private val PROTECTED_TAGS: Set<String> = setOf("html", "body", "main", "article")

    private val BLOCK: Set<String> = setOf(
        "address", "article", "aside", "blockquote", "body", "center", "dd", "details",
        "div", "dl", "dt", "fieldset", "figcaption", "figure", "footer", "form", "h1",
        "h2", "h3", "h4", "h5", "h6", "header", "hr", "html", "li", "main", "nav", "ol",
        "p", "pre", "section", "summary", "table", "ul", "#root", "tbody", "thead",
        "tfoot", "tr", "td", "th", "caption",
    )

    /** An open `<p>` ends when one of these starts. */
    private val CLOSES_P: Set<String> = setOf(
        "address", "article", "aside", "blockquote", "details", "div", "dl", "fieldset",
        "figure", "footer", "form", "h1", "h2", "h3", "h4", "h5", "h6", "header", "hr",
        "main", "nav", "ol", "p", "pre", "section", "table", "ul",
    )

    private class Parsed(val root: El, val title: String)

    private fun parse(html: String): Parsed {
        val root = El("#root", emptyMap())
        val stack = ArrayList<El>()
        stack.add(root)
        var title = ""
        val n = html.length
        var i = 0

        fun addText(s: String) {
            if (s.isNotEmpty()) stack[stack.size - 1].children.add(Text(s))
        }

        fun closeUpTo(names: Set<String>, stoppers: Set<String>) {
            var k = stack.size - 1
            while (k >= 1) {
                val t = stack[k].tag
                if (t in stoppers) return
                if (t in names) {
                    while (stack.size > k) stack.removeAt(stack.size - 1)
                    return
                }
                k--
            }
        }

        while (i < n) {
            val lt = html.indexOf('<', i)
            if (lt < 0) {
                addText(html.substring(i))
                break
            }
            if (lt > i) addText(html.substring(i, lt))
            if (lt + 1 >= n) {
                addText("<")
                break
            }
            val c = html[lt + 1]
            if (c == '!' || c == '?') {
                if (html.startsWith("<!--", lt)) {
                    val end = html.indexOf("-->", lt + 4)
                    i = if (end < 0) n else end + 3
                } else {
                    val end = html.indexOf('>', lt + 2)
                    i = if (end < 0) n else end + 1
                }
                continue
            }
            if (c == '/') {
                var j = lt + 2
                while (j < n && isNameChar(html[j])) j++
                val name = html.substring(lt + 2, j).lowercase()
                val end = html.indexOf('>', j)
                i = if (end < 0) n else end + 1
                if (name.isEmpty()) continue
                var k = stack.size - 1
                while (k >= 1 && stack[k].tag != name) k--
                if (k >= 1) {
                    while (stack.size > k) stack.removeAt(stack.size - 1)
                }
                continue
            }
            if (!c.isLetter()) {
                addText("<")
                i = lt + 1
                continue
            }
            // Start tag.
            var j = lt + 1
            while (j < n && isNameChar(html[j])) j++
            val name = html.substring(lt + 1, j).lowercase()
            val attrs = LinkedHashMap<String, String>()
            var selfClosing = false
            while (j < n) {
                while (j < n && html[j].isWhitespace()) j++
                if (j >= n) break
                val ch = html[j]
                if (ch == '>') {
                    j++
                    break
                }
                if (ch == '/') {
                    if (j + 1 < n && html[j + 1] == '>') {
                        selfClosing = true
                        j += 2
                        break
                    }
                    j++
                    continue
                }
                val nameStart = j
                while (j < n && !html[j].isWhitespace() && html[j] != '=' && html[j] != '>' && html[j] != '/') j++
                val attrName = html.substring(nameStart, j).lowercase()
                while (j < n && html[j].isWhitespace()) j++
                var value = ""
                if (j < n && html[j] == '=') {
                    j++
                    while (j < n && html[j].isWhitespace()) j++
                    if (j < n && (html[j] == '"' || html[j] == '\'')) {
                        val quote = html[j]
                        val close = html.indexOf(quote, j + 1)
                        if (close < 0) {
                            value = html.substring(j + 1)
                            j = n
                        } else {
                            value = html.substring(j + 1, close)
                            j = close + 1
                        }
                    } else {
                        val valueStart = j
                        while (j < n && !html[j].isWhitespace() && html[j] != '>') j++
                        value = html.substring(valueStart, j)
                    }
                }
                if (attrName.isNotEmpty() && !attrs.containsKey(attrName)) {
                    attrs[attrName] = HtmlText.decodeEntities(value)
                }
            }
            i = j

            if (name == "title" && title.isEmpty() && stack.none { it.tag == "svg" }) {
                val end = html.indexOf("</title", i, ignoreCase = true)
                val rawTitle = if (end < 0) "" else html.substring(i, end)
                title = collapse(HtmlText.decodeEntities(rawTitle))
                if (end >= 0) {
                    val gt = html.indexOf('>', end)
                    i = if (gt < 0) n else gt + 1
                }
                continue
            }
            if (name in RAW_SKIP) {
                if (!selfClosing) {
                    val end = html.indexOf("</$name", i, ignoreCase = true)
                    if (end < 0) {
                        i = n
                    } else {
                        val gt = html.indexOf('>', end)
                        i = if (gt < 0) n else gt + 1
                    }
                }
                continue
            }

            // Implicit end tags.
            if (name in CLOSES_P && stack[stack.size - 1].tag == "p") stack.removeAt(stack.size - 1)
            when (name) {
                "li" -> closeUpTo(setOf("li"), setOf("ul", "ol", "table"))
                "dt", "dd" -> closeUpTo(setOf("dt", "dd"), setOf("dl", "table"))
                "tr" -> closeUpTo(setOf("tr"), setOf("table"))
                "td", "th" -> closeUpTo(setOf("td", "th"), setOf("tr", "table"))
                "thead", "tbody", "tfoot" -> closeUpTo(setOf("thead", "tbody", "tfoot"), setOf("table"))
                // </head> is optional in HTML; without this a page that omits it
                // would have its whole body parsed as a child of <head> and dropped.
                "body" -> closeUpTo(setOf("head"), emptySet())
                else -> {}
            }

            val el = El(name, attrs)
            stack[stack.size - 1].children.add(el)
            val closed = name in VOID || (selfClosing && name !in BLOCK && name != "a" && name != "span")
            if (!closed && stack.size < MAX_DEPTH) stack.add(el)
        }
        return Parsed(root, title)
    }

    private fun isNameChar(c: Char): Boolean = c.isLetterOrDigit() || c == '-' || c == ':' || c == '_'

    // ── pruning ─────────────────────────────────────────────────────────────

    private fun isHidden(el: El): Boolean {
        if (el.attrs.containsKey("hidden")) return true
        if (el.attrs["aria-hidden"].equals("true", ignoreCase = true)) return true
        val style = el.attrs["style"] ?: return false
        return Regex("(?i)display\\s*:\\s*none|visibility\\s*:\\s*hidden").containsMatchIn(style)
    }

    private fun hardPrune(el: El) {
        val it = el.children.iterator()
        while (it.hasNext()) {
            val child = it.next()
            if (child is El) {
                if (child.tag in HARD_DROP || isHidden(child)) it.remove() else hardPrune(child)
            }
        }
    }

    /** Fills [El.textLen] / [El.linkLen] bottom-up; returns this node's text length. */
    private fun measure(el: El, inLink: Boolean): Int {
        var text = 0
        var link = 0
        val linkHere = inLink || el.tag == "a"
        for (child in el.children) {
            when (child) {
                is Text -> {
                    val len = visibleLength(child.text)
                    text += len
                    if (linkHere) link += len
                }
                is El -> {
                    text += measure(child, linkHere)
                    link += child.linkLen
                }
            }
        }
        el.textLen = text
        el.linkLen = link
        return text
    }

    private fun visibleLength(s: String): Int {
        var count = 0
        for (ch in s) if (!ch.isWhitespace()) count++
        return count
    }

    private fun hasNoiseName(el: El): Boolean {
        val names = (el.attrs["class"].orEmpty() + " " + el.attrs["id"].orEmpty()).lowercase()
        if (names.isBlank()) return false
        for (token in names.split(Regex("[^a-z0-9]+"))) {
            if (token in NOISE_TOKENS) return true
        }
        return false
    }

    private fun containsTag(el: El, tags: Set<String>): Boolean {
        for (child in el.children) {
            if (child is El && (child.tag in tags || containsTag(child, tags))) return true
        }
        return false
    }

    private fun isChrome(el: El, totalText: Int, inArticle: Boolean): Boolean {
        if (el.tag in PROTECTED_TAGS) return false
        if (el.tag in CHROME_TAGS) return true
        // The site header is furniture; an article's own header (title, byline,
        // date) is content.
        if (el.tag == "header" && !inArticle && !containsTag(el, setOf("h1"))) return true
        val role = el.attrs["role"]?.lowercase()
        if (role != null && role in CHROME_ROLES) return true
        // A wrapper called "page-with-sidebar" holds the article too: a name only
        // condemns a node that does not carry a large share of the page.
        if (hasNoiseName(el) && el.textLen * 10 < totalText * 3) return true
        return false
    }

    private fun chromePrune(el: El, totalText: Int, inArticle: Boolean) {
        val it = el.children.iterator()
        while (it.hasNext()) {
            val child = it.next()
            if (child is El) {
                if (isChrome(child, totalText, inArticle)) {
                    it.remove()
                } else {
                    chromePrune(child, totalText, inArticle || child.tag == "article" || child.tag == "main")
                }
            }
        }
    }

    // ── body selection ──────────────────────────────────────────────────────

    private fun collect(el: El, tag: String, out: MutableList<El>) {
        for (child in el.children) {
            if (child is El) {
                if (child.tag == tag) out.add(child)
                collect(child, tag, out)
            }
        }
    }

    private fun findMain(el: El): El? {
        for (child in el.children) {
            if (child is El) {
                if (child.tag == "main" || child.attrs["role"].equals("main", ignoreCase = true)) return child
                val deeper = findMain(child)
                if (deeper != null) return deeper
            }
        }
        return null
    }

    private fun selectBody(root: El): Pair<El, String> {
        val total = root.textLen
        if (total == 0) return Pair(root, "body")
        val articles = ArrayList<El>()
        collect(root, "article", articles)
        val bestArticle = articles.maxByOrNull { it.textLen }
        // One dominant article is the page; many small ones are a listing.
        if (bestArticle != null && bestArticle.textLen * 10 >= total * 4) return Pair(bestArticle, "article")
        val main = findMain(root)
        if (main != null && main.textLen * 10 >= total * 3) return Pair(main, "main")

        // Density walk: follow the child that still holds most of the prose.
        val nonLinkTotal = total - root.linkLen
        if (nonLinkTotal < 400) return Pair(root, "body")
        var current = root
        var moved = false
        while (true) {
            val prose = current.textLen - current.linkLen
            var next: El? = null
            for (child in current.children) {
                if (child is El && child.tag != "table" && child.tag != "ul" && child.tag != "ol" &&
                    child.tag != "pre" && child.tag != "p" && child.tag != "blockquote"
                ) {
                    val childProse = child.textLen - child.linkLen
                    if (childProse * 10 >= prose * 7 && childProse * 2 >= nonLinkTotal) {
                        next = child
                        break
                    }
                }
            }
            if (next == null) break
            current = next
            moved = true
        }
        return Pair(current, if (moved) "density" else "body")
    }

    // ── rendering ───────────────────────────────────────────────────────────

    private class Ctx(val baseUrl: String?, val markdown: Boolean)

    private fun collapse(s: String): String = s.replace(Regex("[\\s\\u00a0]+"), " ").trim()

    private fun absolute(href: String, baseUrl: String?): String {
        val raw = href.trim()
        if (raw.isEmpty() || baseUrl.isNullOrBlank()) return raw
        return try {
            URI(baseUrl).resolve(raw).toString()
        } catch (_: Exception) {
            raw
        }
    }

    private fun navigable(href: String): Boolean {
        val h = href.trim().lowercase()
        return h.isNotEmpty() && !h.startsWith("#") && !h.startsWith("javascript:") &&
            !h.startsWith("data:") && !h.startsWith("mailto:") && !h.startsWith("tel:")
    }

    /** Raw text of a subtree, whitespace preserved (`<br>` = newline): for `<pre>`. */
    private fun rawText(el: El, out: StringBuilder) {
        for (child in el.children) {
            when (child) {
                is Text -> out.append(HtmlText.decodeEntities(child.text))
                is El -> if (child.tag == "br") out.append('\n') else rawText(child, out)
            }
        }
    }

    /** Inline rendering: whitespace is collapsed by the caller, `\n` marks a hard break. */
    private fun inline(el: El, ctx: Ctx, out: StringBuilder, depth: Int) {
        for (child in el.children) {
            when (child) {
                is Text -> out.append(HtmlText.decodeEntities(child.text).replace('\n', ' ').replace('\r', ' '))
                is El -> inlineEl(child, ctx, out, depth + 1)
            }
        }
    }

    private fun inlineOf(el: El, ctx: Ctx, depth: Int): String {
        val sb = StringBuilder()
        inline(el, ctx, sb, depth)
        return tidyInline(sb.toString())
    }

    /** Collapse spaces per line, keep explicit line breaks. */
    private fun tidyInline(s: String): String =
        s.split('\n').joinToString("\n") { it.replace(Regex("[ \\t\\u00a0\\u000c]+"), " ").trim() }.trim('\n', ' ')

    private fun wrap(marker: String, inner: String, ctx: Ctx, out: StringBuilder) {
        val text = inner.trim()
        if (text.isEmpty()) return
        if (!ctx.markdown || text.contains('\n')) {
            out.append(inner)
            return
        }
        if (inner.startsWith(" ")) out.append(' ')
        out.append(marker).append(text).append(marker)
        if (inner.endsWith(" ")) out.append(' ')
    }

    private fun inlineEl(el: El, ctx: Ctx, out: StringBuilder, depth: Int) {
        if (depth > MAX_DEPTH + 8) return
        when (el.tag) {
            "br" -> out.append('\n')
            "img" -> {
                val alt = collapse(el.attrs["alt"].orEmpty())
                if (alt.isEmpty()) return
                val src = el.attrs["src"].orEmpty()
                if (ctx.markdown && navigable(src)) {
                    out.append("![").append(alt).append("](").append(absolute(src, ctx.baseUrl)).append(')')
                } else {
                    out.append(alt)
                }
            }
            "a" -> {
                val sb = StringBuilder()
                inline(el, ctx, sb, depth)
                val text = collapse(sb.toString())
                val href = el.attrs["href"].orEmpty()
                if (text.isEmpty()) return
                if (ctx.markdown && navigable(href)) {
                    val target = absolute(href, ctx.baseUrl)
                    if (text == target) out.append(target) else out.append('[').append(text).append("](").append(target).append(')')
                } else {
                    out.append(text)
                }
            }
            "strong", "b" -> {
                val sb = StringBuilder()
                inline(el, ctx, sb, depth)
                wrap("**", sb.toString(), ctx, out)
            }
            "em", "i" -> {
                val sb = StringBuilder()
                inline(el, ctx, sb, depth)
                wrap("*", sb.toString(), ctx, out)
            }
            "del", "s", "strike" -> {
                val sb = StringBuilder()
                inline(el, ctx, sb, depth)
                wrap("~~", sb.toString(), ctx, out)
            }
            "code", "kbd", "samp", "tt" -> {
                val sb = StringBuilder()
                rawText(el, sb)
                val text = collapse(sb.toString())
                if (text.isEmpty()) return
                if (ctx.markdown) {
                    val tick = if (text.contains('`')) "``" else "`"
                    val pad = if (text.contains('`')) " " else ""
                    out.append(tick).append(pad).append(text).append(pad).append(tick)
                } else {
                    out.append(text)
                }
            }
            else -> {
                // A block element reached through an inline parent (`<a><div>…`):
                // keep the words, separated, rather than gluing them together.
                val block = el.tag in BLOCK
                if (block) out.append(' ')
                inline(el, ctx, out, depth)
                if (block) out.append(' ')
            }
        }
    }

    /** True when [el] must start its own block. */
    private fun isBlock(el: El): Boolean = el.tag in BLOCK

    private fun blocks(el: El, ctx: Ctx, depth: Int): List<String> {
        val out = ArrayList<String>()
        if (depth > MAX_DEPTH + 8) return out
        val run = StringBuilder()

        fun flush() {
            val text = tidyInline(run.toString())
            run.setLength(0)
            if (text.isNotEmpty()) out.add(text)
        }

        for (child in el.children) {
            when (child) {
                is Text -> run.append(HtmlText.decodeEntities(child.text).replace('\n', ' ').replace('\r', ' '))
                is El -> {
                    if (isBlock(child)) {
                        flush()
                        out.addAll(block(child, ctx, depth + 1))
                    } else {
                        inlineEl(child, ctx, run, depth + 1)
                    }
                }
            }
        }
        flush()
        return out
    }

    private fun block(el: El, ctx: Ctx, depth: Int): List<String> {
        when (el.tag) {
            "h1", "h2", "h3", "h4", "h5", "h6" -> {
                val text = inlineOf(el, ctx, depth).replace('\n', ' ')
                if (text.isEmpty()) return emptyList()
                val level = el.tag[1] - '0'
                return listOf(if (ctx.markdown) "#".repeat(level) + " " + text else text)
            }
            "p" -> {
                // A <p> can still hold blocks after error recovery; reuse the general path.
                return blocks(el, ctx, depth)
            }
            "hr" -> return listOf(if (ctx.markdown) "---" else "")
            "pre" -> return listOf(pre(el, ctx))
            "ul", "ol" -> {
                val list = list(el, ctx, depth)
                return if (list.isEmpty()) emptyList() else listOf(list)
            }
            "blockquote" -> {
                val inner = blocks(el, ctx, depth).joinToString("\n\n")
                if (inner.isBlank()) return emptyList()
                if (!ctx.markdown) return listOf(inner)
                return listOf(inner.split('\n').joinToString("\n") { if (it.isEmpty()) ">" else "> $it" })
            }
            "table" -> return table(el, ctx, depth)
            "dt" -> {
                val text = inlineOf(el, ctx, depth)
                if (text.isEmpty()) return emptyList()
                return listOf(if (ctx.markdown) "**$text**" else text)
            }
            "li" -> {
                // A stray <li> outside any list.
                val inner = blocks(el, ctx, depth).joinToString("\n")
                return if (inner.isBlank()) emptyList() else listOf("- " + inner.replace("\n", "\n  "))
            }
            else -> return blocks(el, ctx, depth)
        }
    }

    private fun pre(el: El, ctx: Ctx): String {
        val sb = StringBuilder()
        rawText(el, sb)
        val code = sb.toString().replace("\r\n", "\n").trim('\n')
        if (!ctx.markdown) return code
        var language = languageOf(el)
        if (language.isEmpty()) {
            for (child in el.children) {
                if (child is El && child.tag == "code") {
                    language = languageOf(child)
                    break
                }
            }
        }
        var fence = "```"
        while (code.contains(fence)) fence += "`"
        return fence + language + "\n" + code + "\n" + fence
    }

    private fun languageOf(el: El): String {
        val cls = el.attrs["class"].orEmpty()
        val m = Regex("(?i)(?:^|\\s)(?:language|lang|highlight-source)-([a-z0-9+#_.-]{1,24})").find(cls)
        val fromClass = m?.groupValues?.get(1)?.lowercase().orEmpty()
        if (fromClass.isNotEmpty()) return fromClass
        val data = el.attrs["data-lang"] ?: el.attrs["data-language"] ?: ""
        return if (data.length in 1..24 && data.all { it.isLetterOrDigit() || it in "+#_.-" }) data.lowercase() else ""
    }

    private fun list(el: El, ctx: Ctx, depth: Int): String {
        val ordered = el.tag == "ol"
        var number = el.attrs["start"]?.trim()?.toIntOrNull() ?: 1
        val lines = ArrayList<String>()
        for (child in el.children) {
            if (child !is El) continue
            if (child.tag != "li") {
                // Markup like <ul><div><li>…: look one level through.
                if (child.tag == "ul" || child.tag == "ol") {
                    val nested = list(child, ctx, depth + 1)
                    if (nested.isNotEmpty()) lines.add(nested.split('\n').joinToString("\n") { "  $it" })
                } else {
                    val nested = list(child, ctx, depth + 1)
                    if (nested.isNotEmpty()) lines.add(nested)
                }
                continue
            }
            val inner = blocks(child, ctx, depth + 1).joinToString("\n")
            if (inner.isBlank()) continue
            val marker = if (ordered) "${number}. " else "- "
            number++
            val pad = " ".repeat(marker.length)
            lines.add(marker + inner.split('\n').joinToString("\n") { if (it.isEmpty()) it else pad + it }.trimStart())
        }
        return lines.joinToString("\n")
    }

    private fun rowsOf(el: El, out: MutableList<El>) {
        for (child in el.children) {
            if (child !is El) continue
            when (child.tag) {
                "tr" -> out.add(child)
                "thead", "tbody", "tfoot" -> rowsOf(child, out)
                else -> {}
            }
        }
    }

    private fun table(el: El, ctx: Ctx, depth: Int): List<String> {
        val rows = ArrayList<El>()
        rowsOf(el, rows)
        val out = ArrayList<String>()
        for (child in el.children) {
            if (child is El && child.tag == "caption") {
                val caption = inlineOf(child, ctx, depth)
                if (caption.isNotEmpty()) out.add(if (ctx.markdown) "**$caption**" else caption)
            }
        }
        if (rows.isEmpty()) {
            out.addAll(blocks(el, ctx, depth))
            return out
        }
        val cellRows = ArrayList<List<El>>()
        var columns = 0
        var layout = false
        var cellCount = 0
        var cellText = 0
        for (row in rows) {
            val cells = ArrayList<El>()
            for (c in row.children) {
                if (c is El && (c.tag == "td" || c.tag == "th")) {
                    cells.add(c)
                    cellCount++
                    cellText += c.textLen
                    if (containsTag(c, setOf("table", "ul", "ol", "pre", "h1", "h2", "h3", "blockquote"))) layout = true
                }
            }
            if (cells.isNotEmpty()) {
                cellRows.add(cells)
                if (cells.size > columns) columns = cells.size
            }
        }
        // A one-column table, or one whose cells hold whole sections, is page
        // layout: render what is inside it instead of a grid.
        if (columns < 2 || layout || columns > MAX_TABLE_COLUMNS ||
            (cellCount > 0 && cellText / cellCount > 300)
        ) {
            for (cells in cellRows) for (c in cells) out.addAll(blocks(c, ctx, depth + 1))
            return out
        }
        val lines = ArrayList<String>()
        cellRows.forEachIndexed { index, cells ->
            val texts = ArrayList<String>()
            for (k in 0 until columns) {
                val text = if (k < cells.size) inlineOf(cells[k], ctx, depth + 1) else ""
                texts.add(text.replace('\n', ' ').replace("|", "\\|"))
            }
            if (ctx.markdown) {
                lines.add("| " + texts.joinToString(" | ") + " |")
                if (index == 0) lines.add("|" + " --- |".repeat(columns))
            } else {
                lines.add(texts.joinToString("\t").trimEnd())
            }
        }
        out.add(lines.joinToString("\n"))
        return out
    }

    // ── entry points ────────────────────────────────────────────────────────

    /**
     * @param mainOnly drop site furniture and keep the main content; false keeps
     *   everything a reader could see.
     * @param markdown false renders the same structure as plain text (no link
     *   targets, no emphasis markers, no fences).
     */
    fun convert(
        html: String,
        baseUrl: String? = null,
        mainOnly: Boolean = true,
        markdown: Boolean = true,
    ): Page {
        val parsed = parse(html)
        val root = parsed.root
        hardPrune(root)
        measure(root, false)
        val ctx = Ctx(baseUrl, markdown)

        if (!mainOnly) {
            return Page(parsed.title, finish(blocks(root, ctx, 0)), "full")
        }
        val fullText = root.textLen
        chromePrune(root, fullText, false)
        measure(root, false)
        val (body, mode) = selectBody(root)
        var content = finish(blocks(body, ctx, 0))
        var usedMode = mode
        // Detection that threw away nearly everything is a wrong detection.
        if (body.textLen * 5 < fullText && body.textLen < 600) {
            val again = parse(html)
            hardPrune(again.root)
            measure(again.root, false)
            content = finish(blocks(again.root, ctx, 0))
            usedMode = "full"
        } else if (markdown && parsed.title.isNotEmpty() && !containsTag(body, setOf("h1"))) {
            content = "# " + parsed.title + "\n\n" + content
        }
        return Page(parsed.title, content.trim(), usedMode)
    }

    private fun finish(blocks: List<String>): String =
        blocks.filter { it.isNotBlank() }
            .joinToString("\n\n")
            .trim()
}
