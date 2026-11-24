package com.openminis.app.tools

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** [T-web-m5] One comparison key per page; the shown URL only loses its tracking. */
class WebUrlCanonicalizerTest {

    @Test
    fun `tracking parameters are removed and real ones keep their order`() {
        assertEquals(
            "https://example.com/a?id=7&page=2",
            WebUrlCanonicalizer.stripTracking("https://example.com/a?utm_source=x&id=7&fbclid=abc&page=2#top"),
        )
        assertEquals("https://example.com/a", WebUrlCanonicalizer.stripTracking("https://example.com/a?utm_medium=mail&gclid=1"))
    }

    @Test
    fun `stripTracking leaves host case path and non http input alone`() {
        assertEquals("https://Example.com/Path/", WebUrlCanonicalizer.stripTracking("https://Example.com/Path/"))
        assertEquals("ftp://example.com/x?utm_source=a", WebUrlCanonicalizer.stripTracking("ftp://example.com/x?utm_source=a"))
        assertEquals("not a url", WebUrlCanonicalizer.stripTracking(" not a url "))
    }

    @Test
    fun `the same page under different spellings has one key`() {
        val key = WebUrlCanonicalizer.key("https://example.com/docs")
        assertEquals(key, WebUrlCanonicalizer.key("http://www.example.com/docs/"))
        assertEquals(key, WebUrlCanonicalizer.key("https://EXAMPLE.com:443/docs/index.html"))
        assertEquals(key, WebUrlCanonicalizer.key("https://example.com/docs?utm_campaign=x#intro"))
        assertEquals(key, WebUrlCanonicalizer.key("https://m.example.com/docs"))
    }

    @Test
    fun `different pages keep different keys`() {
        assertNotEquals(WebUrlCanonicalizer.key("https://example.com/a?id=1"), WebUrlCanonicalizer.key("https://example.com/a?id=2"))
        assertNotEquals(WebUrlCanonicalizer.key("https://example.com/Docs"), WebUrlCanonicalizer.key("https://example.com/docs"))
        assertNotEquals(WebUrlCanonicalizer.key("https://example.com:8443/a"), WebUrlCanonicalizer.key("https://example.com/a"))
        assertNotEquals(WebUrlCanonicalizer.key("https://docs.example.com/a"), WebUrlCanonicalizer.key("https://example.com/a"))
    }

    @Test
    fun `an unparsable url still yields a usable key and host`() {
        val odd = "https://example.com/a b|c"
        assertTrue(WebUrlCanonicalizer.key(odd).isNotEmpty())
        assertEquals("example.com", WebUrlCanonicalizer.host(odd))
        assertEquals("", WebUrlCanonicalizer.host("nonsense"))
    }

    @Test
    fun `www is only dropped when a registrable name remains`() {
        assertEquals("example.com", WebUrlCanonicalizer.host("https://www.example.com/x"))
        assertEquals("www.com", WebUrlCanonicalizer.host("https://www.com/x"))
    }

    @Test
    fun `domain patterns reduce to a bare host`() {
        assertEquals("example.com", WebUrlCanonicalizer.normalizeDomain("https://www.Example.com/docs?x=1"))
        assertEquals("example.com", WebUrlCanonicalizer.normalizeDomain("*.example.com"))
        assertEquals("example.com", WebUrlCanonicalizer.normalizeDomain("site:example.com"))
        assertEquals("example.com", WebUrlCanonicalizer.normalizeDomain(".example.com:443"))
        assertEquals("", WebUrlCanonicalizer.normalizeDomain("exa mple"))
        assertEquals("", WebUrlCanonicalizer.normalizeDomain("  "))
    }

    @Test
    fun `a domain matches itself and its subdomains only`() {
        assertTrue(WebUrlCanonicalizer.hostMatches("example.com", "example.com"))
        assertTrue(WebUrlCanonicalizer.hostMatches("docs.example.com", "example.com"))
        assertFalse(WebUrlCanonicalizer.hostMatches("notexample.com", "example.com"))
        assertFalse(WebUrlCanonicalizer.hostMatches("example.com.evil.net", "example.com"))
        assertFalse(WebUrlCanonicalizer.hostMatches("", "example.com"))
    }
}
