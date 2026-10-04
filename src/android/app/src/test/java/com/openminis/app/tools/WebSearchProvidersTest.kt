package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * [T-android-web-search] The provider set ported from Kelivo's
 * `lib/core/services/search/providers/`: one case per response shape that the
 * generic walker does not already cover, plus the multi-key machinery
 * ("multiple search") that lets several keys back one engine.
 *
 * Parser inputs are recorded payloads, trimmed to the fields the parser reads, so
 * these tests never touch the network — the same discipline as
 * [WebSearchToolTest] / [WebSearchMultiEngineTest].
 */
class WebSearchProvidersTest {

    @Before
    fun setUp() {
        SearchKeyRotator.reset()
    }

    // ── vendor-specific shapes ──────────────────────────────────────────────

    @Test
    fun `doubao pascal-case envelope with Summary over Content`() {
        val json = """
            {"ResponseMetadata":{"Error":null},
             "Result":{"WebResults":[
               {"Title":"豆包结果","Url":"https://d.example","Summary":"summary text","Content":"long content"},
               {"Title":"","Url":"","Summary":"dropped: no url"}
             ]}}
        """.trimIndent()
        val results = WebSearchTool.parseDoubaoJson(json, max = 8)
        assertEquals(1, results.size)
        assertEquals("https://d.example", results[0].url)
        assertEquals("summary text", results[0].snippet)
    }

    @Test
    fun `doubao surfaces the API error instead of an empty list`() {
        val json = """{"ResponseMetadata":{"Error":{"Code":"InvalidAccessKey","Message":"bad key"}}}"""
        val error = runCatching { WebSearchTool.parseDoubaoJson(json) }.exceptionOrNull()
        assertEquals("bad key", error?.message)
    }

    @Test
    fun `kimi joins chunk text with a blank line`() {
        val json = """
            {"search_results":[
              {"title":"Kimi hit","url":"https://k.example",
               "chunks":[{"text":"first part"},{"text":""},{"text":"second part"}]},
              {"title":"no url","url":"","chunks":[{"text":"x"}]}
            ]}
        """.trimIndent()
        val results = WebSearchTool.parseKimiJson(json, max = 5)
        assertEquals(1, results.size)
        assertEquals("first part\n\nsecond part", results[0].snippet)
    }

    @Test
    fun `grok keeps only url_citation annotations, deduplicated`() {
        val json = """
            {"output":[
              {"type":"reasoning","content":[{"type":"output_text","text":"thinking"}]},
              {"type":"message","content":[
                {"type":"output_text","text":"answer text",
                 "annotations":[
                   {"type":"url_citation","url":"https://a.example","title":"A"},
                   {"type":"url_citation","url":"https://a.example","title":"A again"},
                   {"type":"url_citation","url":"https://b.example"},
                   {"type":"file_citation","url":"https://ignored.example"}
                 ]}
              ]}
            ]}
        """.trimIndent()
        val results = WebSearchTool.parseGrokJson(json, max = 8)
        assertEquals(listOf("https://a.example", "https://b.example"), results.map { it.url })
        // A citation with no title falls back to the URL rather than an empty row.
        assertEquals("https://b.example", results[1].title)
    }

    // ── shapes the generic walker must reach ────────────────────────────────

    @Test
    fun `kagi nests hits under data search`() {
        val json = """
            {"data":{"search":[
              {"title":"Kagi hit","url":"https://kagi.example","snippet":"k"}
            ]}}
        """.trimIndent()
        val results = WebSearchTool.parseGenericSearchJson(json, max = 5)
        assertEquals(1, results.size)
        assertEquals("https://kagi.example", results[0].url)
    }

    @Test
    fun `metaso uses webpages and link`() {
        val json = """
            {"webpages":[{"title":"Metaso","link":"https://metaso.example","snippet":"m"}]}
        """.trimIndent()
        val results = WebSearchTool.parseGenericSearchJson(json, max = 5)
        assertEquals(1, results.size)
        assertEquals("https://metaso.example", results[0].url)
    }

    @Test
    fun `linkup uses sources`() {
        val json = """
            {"answer":"summary","sources":[{"name":"Linkup src","url":"https://linkup.example","snippet":"l"}]}
        """.trimIndent()
        val results = WebSearchTool.parseGenericSearchJson(json, max = 5)
        assertEquals(1, results.size)
        assertEquals("Linkup src", results[0].title)
    }

    @Test
    fun `querit nests the array under results result`() {
        val json = """
            {"results":{"result":[{"title":"Querit","url":"https://querit.example","sentence":"q"}]}}
        """.trimIndent()
        val results = WebSearchTool.parseGenericSearchJson(json, max = 5)
        assertEquals(1, results.size)
        assertEquals("https://querit.example", results[0].url)
    }

    @Test
    fun `you merges web and news under results`() {
        val json = """
            {"results":{"web":[{"title":"You web","url":"https://you.example","description":"w"}],
                        "news":[{"title":"You news","url":"https://news.example","description":"n"}]}}
        """.trimIndent()
        val results = WebSearchTool.parseGenericSearchJson(json, max = 5)
        assertEquals(listOf("https://you.example", "https://news.example"), results.map { it.url })
    }

    @Test
    fun `brave nests hits under web results`() {
        val json = """
            {"web":{"results":[{"title":"Brave hit","url":"https://brave.example","description":"b"}]}}
        """.trimIndent()
        val results = WebSearchTool.parseGenericSearchJson(json, max = 5)
        assertEquals("https://brave.example", results.single().url)
    }

    @Test
    fun `parallel reads excerpts for the snippet`() {
        val json = """
            {"results":[{"title":"Parallel","url":"https://parallel.example","excerpts":["first excerpt","second"]}]}
        """.trimIndent()
        val results = WebSearchTool.parseGenericSearchJson(json, max = 5)
        assertEquals("first excerpt", results.single().snippet)
    }

    @Test
    fun `firecrawl reads data web and news`() {
        val json = """
            {"data":{"web":[{"title":"FC web","url":"https://fc.example","markdown":"md"}],
                     "news":[{"title":"FC news","url":"https://fcnews.example","markdown":"md2"}]}}
        """.trimIndent()
        val results = WebSearchTool.parseGenericSearchJson(json, max = 8)
        assertEquals(listOf("https://fc.example", "https://fcnews.example"), results.map { it.url })
    }

    @Test
    fun `serper organic results`() {
        val json = """
            {"organic":[{"title":"Serper","link":"https://serper.example","snippet":"s"}]}
        """.trimIndent()
        assertEquals("https://serper.example", WebSearchTool.parseGenericSearchJson(json, max = 5).single().url)
    }

    // ── Bing's HTML results (the "Bing is free" engine) ──────────────────────

    @Test
    fun `bing html parses the current link-wraps-heading markup`() {
        // Verbatim shape from the live endpoint (2026-10-04): the <a> WRAPS the
        // <h2>. The older order (<h2><a href>) — the one Kelivo and the Linux fork
        // parse — matched none of the five blocks on the real page, so this engine
        // returned nothing at all until this test existed.
        val html = """
            <div class="b_algoheader"><a href="https://www.alpine-usa.com/" h="ID=SERP,5098.2">
            <h2 class=""><strong>Alpine</strong> | Car Audio, Stereo, Speakers</h2></a></div>
            <div class="b_caption"><p class="b_lineclamp3">Welcome to Alpine</p></div>
        """.trimIndent()
        val results = WebSearchTool.parseBingHtml(html, max = 5)
        assertEquals(1, results.size)
        assertEquals("https://www.alpine-usa.com/", results[0].url)
        // Tags stripped from the title, entity-decoded.
        assertEquals("Alpine | Car Audio, Stereo, Speakers", results[0].title)
    }

    @Test
    fun `bing html still parses the legacy heading-wraps-link markup`() {
        val html = """<h2><a href="https://legacy.example">Legacy Title</a></h2>"""
        assertEquals("https://legacy.example", WebSearchTool.parseBingHtml(html, max = 5).single().url)
    }

    @Test
    fun `bing html skips the redirect wrapper and microsoft links`() {
        val html = """
            <a href="https://www.bing.com/ck/a?u=abc"><h2>Redirect wrapper</h2></a>
            <a href="https://www.microsoft.com/"><h2>Microsoft</h2></a>
            <a href="https://real.example"><h2>Real</h2></a>
        """.trimIndent()
        val results = WebSearchTool.parseBingHtml(html, max = 5)
        assertEquals(listOf("https://real.example"), results.map { it.url })
    }

    // ── multiple keys ("multiple search") ───────────────────────────────────

    @Test
    fun `key batch splits on newlines commas semicolons and spaces`() {
        val parsed = WebSearchSettings.parseKeyBatch(
            "key-one\nkey-two,key-three; key-four   key-one",
        )
        // Order preserved, duplicates dropped.
        assertEquals(listOf("key-one", "key-two", "key-three", "key-four"), parsed)
    }

    @Test
    fun `masked key keeps four characters at each end`() {
        assertEquals("abcd••••wxyz", WebSearchSettings.maskKey("abcdefghijklwxyz"))
        // Short keys reveal nothing at all.
        assertEquals("••••••••", WebSearchSettings.maskKey("short"))
    }

    @Test
    fun `rotation cycles through the pool and never advances a single key`() {
        val pool = listOf("k1", "k2", "k3")
        assertEquals(listOf("k1", "k2", "k3", "k1"), (1..4).map { SearchKeyRotator.next("tavily", pool) })
        // A one-key pool must be stable — that is the behaviour every install
        // had before multiple keys existed.
        assertEquals(listOf("only", "only"), (1..2).map { SearchKeyRotator.next("kagi", listOf("only")) })
        assertEquals("", SearchKeyRotator.next("empty", emptyList()))
    }

    @Test
    fun `rotation is per engine`() {
        assertEquals("a1", SearchKeyRotator.next("engine-a", listOf("a1", "a2")))
        // A different engine starts at its own first key, not where the other left off.
        assertEquals("b1", SearchKeyRotator.next("engine-b", listOf("b1", "b2")))
        assertEquals("a2", SearchKeyRotator.next("engine-a", listOf("a1", "a2")))
    }

    @Test
    fun `every engine has an id, a name and a coherent key story`() {
        for (engine in WebSearchSettings.Engine.entries) {
            assertTrue("engine ${engine.name} needs an id", engine.id.isNotBlank())
            assertTrue("engine ${engine.name} needs a display name", engine.displayName.isNotBlank())
            if (!engine.needsKey) {
                assertNull("${engine.id} should not require a key", engine.urlKey?.let { null })
            }
            // A URL override key must not collide with another engine's.
            engine.urlKey?.let { key ->
                assertEquals(
                    "url override key $key is used by more than one engine",
                    1,
                    WebSearchSettings.Engine.entries.count { it.urlKey == key },
                )
            }
        }
        // No-key engines first: the list is the settings order and the tool has to
        // work on a fresh install.
        assertEquals(WebSearchSettings.Engine.DDG, WebSearchSettings.Engine.entries.first())
    }
}
