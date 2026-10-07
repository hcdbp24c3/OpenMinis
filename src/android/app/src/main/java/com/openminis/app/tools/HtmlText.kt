package com.openminis.app.tools

/**
 * [T-html-text] HTML entity decoding shared by every tool that scrapes a page or a
 * search result.
 *
 * Why shared: [WebSearchTool] had a three-entry `htmlUnescape` (amp/lt/gt) and
 * [WebFetchTool] a ten-entry `decodeEntities`, and neither handled NUMERIC references.
 * That is the form real pages actually emit for anything outside ASCII — DuckDuckGo
 * returns `Thời tiết h&#224;ng giờ ở H&#224; Nội` — so a Vietnamese (or French, or
 * Polish) search result reached the model with raw `&#224;` in the title: valid text,
 * useless to read and wrong if quoted.
 *
 * Numeric decoding is the primary path and is exact:
 *  - `&#224;` and `&#xE0;` both decode by code point;
 *  - an astral code point (`&#x1F600;`) becomes a surrogate pair via
 *    [Character.toChars];
 *  - a reference that is ITSELF a surrogate half — pages frequently split an emoji into
 *    `&#55357;&#56832;` — is emitted as that code unit so the two halves recombine in
 *    the string, which is what the author of the page meant;
 *  - anything out of range, empty, or unterminated is left EXACTLY as it was: a
 *    decoder that silently drops text is worse than one that leaves `&#xZZ;` visible.
 *
 * The named table is deliberately small — the entities that survive in real markup
 * (structural, quoting, currency) plus the Latin-1 accented letters that appear in
 * titles. Everything else is numeric in practice, and every named entry here is
 * generated from one table so the two values cannot drift.
 *
 * iOS twin: `Agent/HtmlText.swift`, same table and same rules.
 */
object HtmlText {

    /**
     * Codes are written as INT code points, not as characters, so the Latin-1 accents
     * are readable next to their names and cannot be mangled by an editor.
     */
    private val NAMED: Map<String, Int> = mapOf(
        // Structural / quoting: the set that appears in every page.
        "nbsp" to 0x00A0, "amp" to 0x0026, "lt" to 0x003C, "gt" to 0x003E,
        "quot" to 0x0022, "apos" to 0x0027,
        "mdash" to 0x2014, "ndash" to 0x2013, "hellip" to 0x2026, "middot" to 0x00B7,
        "lsquo" to 0x2018, "rsquo" to 0x2019, "ldquo" to 0x201C, "rdquo" to 0x201D,
        "laquo" to 0x00AB, "raquo" to 0x00BB, "bull" to 0x2022, "dagger" to 0x2020,
        "permil" to 0x2030, "prime" to 0x2032, "times" to 0x00D7, "divide" to 0x00F7,
        "plusmn" to 0x00B1, "frac12" to 0x00BD, "frac14" to 0x00BC, "frac34" to 0x00BE,
        "sup2" to 0x00B2, "sup3" to 0x00B3, "deg" to 0x00B0, "micro" to 0x00B5,
        "copy" to 0x00A9, "reg" to 0x00AE, "trade" to 0x2122, "sect" to 0x00A7,
        "para" to 0x00B6, "iexcl" to 0x00A1, "iquest" to 0x00BF, "ordf" to 0x00AA,
        "ordm" to 0x00BA, "acute" to 0x00B4, "cedil" to 0x00B8, "szlig" to 0x00DF,
        "euro" to 0x20AC, "pound" to 0x00A3, "yen" to 0x00A5, "cent" to 0x00A2,
        "curren" to 0x00A4, "brvbar" to 0x00A6, "uml" to 0x00A8, "shy" to 0x00AD,
        "not" to 0x00AC, "macr" to 0x00AF, "sup1" to 0x00B9, "frac13" to 0x2153,
        // Latin-1 letters: the accents a title in Vietnamese, French, Spanish,
        // Portuguese, German or Nordic text actually carries.
        "agrave" to 0x00E0, "aacute" to 0x00E1, "acirc" to 0x00E2, "atilde" to 0x00E3,
        "auml" to 0x00E4, "aring" to 0x00E5, "aelig" to 0x00E6, "ccedil" to 0x00E7,
        "egrave" to 0x00E8, "eacute" to 0x00E9, "ecirc" to 0x00EA, "euml" to 0x00EB,
        "igrave" to 0x00EC, "iacute" to 0x00ED, "icirc" to 0x00EE, "iuml" to 0x00EF,
        "eth" to 0x00F0, "ntilde" to 0x00F1, "ograve" to 0x00F2, "oacute" to 0x00F3,
        "ocirc" to 0x00F4, "otilde" to 0x00F5, "ouml" to 0x00F6, "oslash" to 0x00F8,
        "ugrave" to 0x00F9, "uacute" to 0x00FA, "ucirc" to 0x00FB, "uuml" to 0x00FC,
        "yacute" to 0x00FD, "thorn" to 0x00FE, "yuml" to 0x00FF,
        "Agrave" to 0x00C0, "Aacute" to 0x00C1, "Acirc" to 0x00C2, "Atilde" to 0x00C3,
        "Auml" to 0x00C4, "Aring" to 0x00C5, "AElig" to 0x00C6, "Ccedil" to 0x00C7,
        "Egrave" to 0x00C8, "Eacute" to 0x00C9, "Ecirc" to 0x00CA, "Euml" to 0x00CB,
        "Igrave" to 0x00CC, "Iacute" to 0x00CD, "Icirc" to 0x00CE, "Iuml" to 0x00CF,
        "ETH" to 0x00D0, "Ntilde" to 0x00D1, "Ograve" to 0x00D2, "Oacute" to 0x00D3,
        "Ocirc" to 0x00D4, "Otilde" to 0x00D5, "Ouml" to 0x00D6, "Oslash" to 0x00D8,
        "Ugrave" to 0x00D9, "Uacute" to 0x00DA, "Ucirc" to 0x00DB, "Uuml" to 0x00DC,
        "Yacute" to 0x00DD, "THORN" to 0x00DE,
    )

    /**
     * One pass over [raw], replacing only what is genuinely a reference. The pattern
     * caps the name at 32 characters (longest real entity is `CounterClockwiseContourIntegral`
     * at 31) so a bare `&` followed by prose cannot swallow the rest of the line.
     */
    private val ENTITY = Regex("&(#x[0-9a-fA-F]{1,6}|#[0-9]{1,7}|[a-zA-Z][a-zA-Z0-9]{1,31});")

    fun decodeEntities(raw: String): String {
        if ('&' !in raw) return raw
        return ENTITY.replace(raw) { match ->
            val body = match.groupValues[1]
            when {
                body.startsWith("#x") || body.startsWith("#X") ->
                    codePointOrNull(body.substring(2), 16)?.let(::emit) ?: match.value
                body.startsWith("#") ->
                    codePointOrNull(body.substring(1), 10)?.let(::emit) ?: match.value
                else -> NAMED[body]?.let(::emit) ?: match.value
            }
        }
    }

    private fun codePointOrNull(digits: String, radix: Int): Int? =
        digits.toIntOrNull(radix)?.takeIf { it in 0..0x10FFFF }

    /**
     * A surrogate code point is emitted as a bare char rather than via
     * [Character.toChars] (which throws for it): pages split an emoji into two
     * references, and the pair has to meet in the string to become the character.
     */
    private fun emit(codePoint: Int): String =
        if (codePoint in 0xD800..0xDFFF) codePoint.toChar().toString()
        else String(Character.toChars(codePoint))
}
