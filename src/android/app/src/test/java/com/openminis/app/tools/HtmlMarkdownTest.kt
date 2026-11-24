package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/** [T-web-m5] HTML → Markdown with main-content detection, on sample pages. */
class HtmlMarkdownTest {

    private val filler: String = "Kotlin coroutines make asynchronous code read like sequential code. ".repeat(12)

    private val articlePage: String = """
        <!DOCTYPE html>
        <html><head><title>Release 2.0 &amp; notes</title>
        <style>.x{color:red}</style><script>var tracking = "do not show";</script></head>
        <body>
          <header class="site-header"><a href="/">Home</a> <a href="/blog">Blog</a></header>
          <nav><ul><li><a href="/a">Nav link A</a></li><li><a href="/b">Nav link B</a></li></ul></nav>
          <div id="cookie-banner">We use cookies. <button>Accept</button></div>
          <article>
            <header><h1>Release 2.0</h1><p class="byline">By Ada, 2026</p></header>
            <p>$filler</p>
            <h2>What changed</h2>
            <ul>
              <li>Fixed <strong>timeouts</strong> in <a href="/docs/net">the network layer</a></li>
              <li>Nested:
                <ol><li>first</li><li>second</li></ol>
              </li>
            </ul>
            <pre><code class="language-kotlin">fun main() {
    println("a &lt; b")
}</code></pre>
            <table>
              <tr><th>Version</th><th>Date</th></tr>
              <tr><td>2.0</td><td>2026-11-01</td></tr>
              <tr><td>1.9 | LTS</td><td>2026-06-01</td></tr>
            </table>
            <blockquote><p>Ship it.</p></blockquote>
            <p>Line one<br>line two with <em>emphasis</em> and <code>inline()</code>.</p>
            <div class="share-buttons"><a href="https://twitter.example/share">Share this</a></div>
          </article>
          <aside class="sidebar"><h3>Related posts</h3><a href="/old">Old post</a></aside>
          <footer>Copyright Example Corp</footer>
        </body></html>
    """.trimIndent()

    @Test
    fun `main mode keeps the article and drops the furniture`() {
        val page = HtmlMarkdown.convert(articlePage, "https://example.com/blog/release")
        assertEquals("article", page.mode)
        assertEquals("Release 2.0 & notes", page.title)
        val md = page.content
        assertTrue(md, md.startsWith("# Release 2.0"))
        assertTrue(md.contains("By Ada, 2026"))
        assertTrue(md.contains("## What changed"))
        for (gone in listOf("Nav link A", "We use cookies", "Related posts", "Copyright Example Corp", "Share this", "do not show", "color:red", "Home")) {
            assertFalse("should not contain $gone:\n$md", md.contains(gone))
        }
    }

    @Test
    fun `lists links emphasis and nesting survive`() {
        val md = HtmlMarkdown.convert(articlePage, "https://example.com/blog/release").content
        assertTrue(md, md.contains("- Fixed **timeouts** in [the network layer](https://example.com/docs/net)"))
        assertTrue(md, md.contains("- Nested:"))
        assertTrue(md, md.contains("  1. first\n  2. second"))
        assertTrue(md, md.contains("Line one\nline two with *emphasis* and `inline()`."))
        assertTrue(md, md.contains("> Ship it."))
    }

    @Test
    fun `code blocks keep their language whitespace and decoded entities`() {
        val md = HtmlMarkdown.convert(articlePage, "https://example.com/blog/release").content
        assertTrue(md, md.contains("```kotlin\nfun main() {\n    println(\"a < b\")\n}\n```"))
    }

    @Test
    fun `simple tables become pipe tables with escaped pipes`() {
        val md = HtmlMarkdown.convert(articlePage, "https://example.com/blog/release").content
        assertTrue(md, md.contains("| Version | Date |\n| --- | --- |\n| 2.0 | 2026-11-01 |\n| 1.9 \\| LTS | 2026-06-01 |"))
    }

    @Test
    fun `full mode keeps everything a reader could see but never scripts`() {
        val page = HtmlMarkdown.convert(articlePage, "https://example.com/blog/release", mainOnly = false)
        assertEquals("full", page.mode)
        assertTrue(page.content.contains("Nav link A"))
        assertTrue(page.content.contains("Copyright Example Corp"))
        assertTrue(page.content.contains("## What changed"))
        assertFalse(page.content.contains("do not show"))
    }

    @Test
    fun `plain text mode has no markdown syntax`() {
        val text = HtmlMarkdown.convert(articlePage, "https://example.com/blog/release", mainOnly = true, markdown = false).content
        assertTrue(text, text.contains("Fixed timeouts in the network layer"))
        assertFalse(text.contains("**"))
        assertFalse(text.contains("]("))
        assertFalse(text.contains("```"))
        assertFalse(text.contains("Nav link A"))
        assertTrue(text.contains("println(\"a < b\")"))
    }

    @Test
    fun `main or role main is used when there is no article`() {
        val html = """
            <html><body>
            <div class="navbar"><a href="/x">Products</a><a href="/y">Pricing</a></div>
            <div role="main"><h1>Guide</h1><p>$filler</p></div>
            <div class="footer">Legal words</div>
            </body></html>
        """.trimIndent()
        val page = HtmlMarkdown.convert(html, "https://example.com/")
        assertEquals("main", page.mode)
        assertTrue(page.content.startsWith("# Guide"))
        assertFalse(page.content.contains("Pricing"))
        assertFalse(page.content.contains("Legal words"))
    }

    @Test
    fun `without landmarks the densest container is chosen`() {
        val links = (1..40).joinToString("") { "<a href=\"/l$it\">Menu entry number $it</a> " }
        val html = """
            <html><body><div id="wrap">
            <div id="left">$links</div>
            <div id="content"><h2>Topic</h2><p>$filler</p><p>$filler</p></div>
            </div></body></html>
        """.trimIndent()
        val page = HtmlMarkdown.convert(html, "https://example.com/")
        assertEquals("density", page.mode)
        assertTrue(page.content.contains("## Topic"))
        assertFalse(page.content.contains("Menu entry number 7"))
    }

    @Test
    fun `a listing of many small articles is not reduced to one of them`() {
        val cards = (1..8).joinToString("") { "<article><h2>Post $it</h2><p>Summary of post number $it, a few words long.</p></article>" }
        val page = HtmlMarkdown.convert("<html><body><main>$cards</main></body></html>", null)
        assertTrue(page.content.contains("Post 1"))
        assertTrue(page.content.contains("Post 8"))
    }

    @Test
    fun `a noisy name does not condemn the container that holds the page`() {
        val html = "<html><body><div class=\"page-with-sidebar\"><h1>Doc</h1><p>$filler</p></div></body></html>"
        val page = HtmlMarkdown.convert(html, null)
        assertTrue(page.content.contains("# Doc"))
        assertTrue(page.content.contains("Kotlin coroutines"))
    }

    @Test
    fun `when detection finds almost nothing the whole page comes back`() {
        val html = "<html><body><nav><p>Only a navigation block with some words in it here.</p></nav><article></article></body></html>"
        val page = HtmlMarkdown.convert(html, null)
        assertEquals("full", page.mode)
        assertTrue(page.content.contains("Only a navigation block"))
    }

    @Test
    fun `the title becomes the heading when the body has no h1`() {
        val html = "<html><head><title>Changelog</title></head><body><main><p>$filler</p></main></body></html>"
        assertTrue(HtmlMarkdown.convert(html, null).content.startsWith("# Changelog\n\n"))
    }

    @Test
    fun `hidden nodes are dropped`() {
        val html = "<body><p>Seen</p><p hidden>Gone1</p><div aria-hidden=\"true\">Gone2</div><span style=\"display: none\">Gone3</span></body>"
        val md = HtmlMarkdown.convert(html, null, mainOnly = false).content
        assertEquals("Seen", md)
    }

    @Test
    fun `unclosed and stray tags do not break the tree`() {
        val html = "<body><p>one<p>two</div></span><ul><li>a<li>b</ul><table><tr><td>x<td>y<tr><td>1<td>2</table>tail"
        val md = HtmlMarkdown.convert(html, null, mainOnly = false).content
        assertEquals("one\n\ntwo\n\n- a\n- b\n\n| x | y |\n| --- | --- |\n| 1 | 2 |\n\ntail", md)
    }

    @Test
    fun `a page without a closing head still has a body`() {
        val md = HtmlMarkdown.convert("<html><head><meta charset=utf-8><body><p>Hello</p></body></html>", null, mainOnly = false).content
        assertEquals("Hello", md)
    }

    @Test
    fun `links that go nowhere keep only their text and images keep alt text`() {
        val html = "<body><p><a href=\"javascript:void(0)\">Click</a> <a href=\"#top\">Top</a> <a href=\"/p\"><img src=\"/i.png\" alt=\"Logo\"></a> <img src=\"/x.png\"></p></body>"
        val md = HtmlMarkdown.convert(html, "https://example.com/dir/page", mainOnly = false).content
        assertEquals("Click Top [![Logo](https://example.com/i.png)](https://example.com/p)", md)
    }

    @Test
    fun `layout tables are flattened instead of rendered as a grid`() {
        val html = "<body><table><tr><td><h2>Left</h2><ul><li>a</li></ul></td><td><p>Right</p></td></tr></table></body>"
        val md = HtmlMarkdown.convert(html, null, mainOnly = false).content
        assertFalse(md.contains("|"))
        assertTrue(md.contains("## Left"))
        assertTrue(md.contains("- a"))
        assertTrue(md.contains("Right"))
    }

    @Test
    fun `a code block containing a fence gets a longer fence`() {
        val md = HtmlMarkdown.convert("<body><pre>```\ninner\n```</pre></body>", null, mainOnly = false).content
        assertTrue(md.startsWith("````\n"))
        assertTrue(md.endsWith("\n````"))
    }

    @Test
    fun `pathological nesting neither overflows nor loses the text`() {
        val deep = "<div>".repeat(5000) + "deep text" + "</div>".repeat(5000)
        val md = HtmlMarkdown.convert("<body>$deep</body>", null, mainOnly = false).content
        assertTrue(md.contains("deep text"))
        val spans = "<b>".repeat(5000) + "bold text"
        assertTrue(HtmlMarkdown.convert("<body><p>$spans</p></body>", null).content.contains("bold text"))
    }

    @Test
    fun `text that is not html comes back as text`() {
        assertEquals("a < b and c > d", HtmlMarkdown.convert("a < b and c > d", null, mainOnly = false).content)
        assertEquals("", HtmlMarkdown.convert("", null).content)
    }
}
