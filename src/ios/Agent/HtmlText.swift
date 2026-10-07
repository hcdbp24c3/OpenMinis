import Foundation

/// [T-html-text] HTML entity decoding shared by every Swift tool that scrapes a page
/// or a search result. Android twin: `tools/HtmlText.kt` — same table, same rules, so
/// the two platforms cannot disagree about what a title says.
///
/// Why shared: `WebSearchTool` had a six-entry `htmlUnescape` and `WebFetchTool` a
/// ten-entry `decodeEntities`, and neither handled NUMERIC references. That is the form
/// real pages emit for anything outside ASCII — DuckDuckGo returns
/// `Thời tiết H&#224;ng giờ ở H&#224; Nội` — so a Vietnamese (or French, or Polish)
/// result reached the model with raw `&#224;` in the title: valid text, useless to read
/// and wrong if quoted.
///
/// Numeric decoding is exact:
///  - `&#224;` and `&#xE0;` both decode by code point;
///  - an astral code point (`&#x1F600;`) becomes a surrogate pair, which Swift's
///    `UnicodeScalar` handles directly;
///  - a reference that is ITSELF a surrogate half — pages frequently split an emoji
///    into `&#55357;&#56832;` — is emitted as that UTF-16 code unit so the two halves
///    recombine, which is what the page's author meant;
///  - anything out of range, empty, or unterminated is left EXACTLY as it was: a
///    decoder that silently drops text is worse than one that leaves `&#xZZ;` visible.
enum HtmlText {

    /// Codes are INT code points, not characters, so the Latin-1 accents stay readable
    /// next to their names and cannot be mangled by an editor.
    private static let named: [String: UInt32] = [
        // Structural / quoting.
        "nbsp": 0x00A0, "amp": 0x0026, "lt": 0x003C, "gt": 0x003E,
        "quot": 0x0022, "apos": 0x0027,
        "mdash": 0x2014, "ndash": 0x2013, "hellip": 0x2026, "middot": 0x00B7,
        "lsquo": 0x2018, "rsquo": 0x2019, "ldquo": 0x201C, "rdquo": 0x201D,
        "laquo": 0x00AB, "raquo": 0x00BB, "bull": 0x2022, "dagger": 0x2020,
        "permil": 0x2030, "prime": 0x2032, "times": 0x00D7, "divide": 0x00F7,
        "plusmn": 0x00B1, "frac12": 0x00BD, "frac14": 0x00BC, "frac34": 0x00BE,
        "sup2": 0x00B2, "sup3": 0x00B3, "deg": 0x00B0, "micro": 0x00B5,
        "copy": 0x00A9, "reg": 0x00AE, "trade": 0x2122, "sect": 0x00A7,
        "para": 0x00B6, "iexcl": 0x00A1, "iquest": 0x00BF, "ordf": 0x00AA,
        "ordm": 0x00BA, "acute": 0x00B4, "cedil": 0x00B8, "szlig": 0x00DF,
        "euro": 0x20AC, "pound": 0x00A3, "yen": 0x00A5, "cent": 0x00A2,
        "curren": 0x00A4, "brvbar": 0x00A6, "uml": 0x00A8, "shy": 0x00AD,
        "not": 0x00AC, "macr": 0x00AF, "sup1": 0x00B9, "frac13": 0x2153,
        // Latin-1 letters.
        "agrave": 0x00E0, "aacute": 0x00E1, "acirc": 0x00E2, "atilde": 0x00E3,
        "auml": 0x00E4, "aring": 0x00E5, "aelig": 0x00E6, "ccedil": 0x00E7,
        "egrave": 0x00E8, "eacute": 0x00E9, "ecirc": 0x00EA, "euml": 0x00EB,
        "igrave": 0x00EC, "iacute": 0x00ED, "icirc": 0x00EE, "iuml": 0x00EF,
        "eth": 0x00F0, "ntilde": 0x00F1, "ograve": 0x00F2, "oacute": 0x00F3,
        "ocirc": 0x00F4, "otilde": 0x00F5, "ouml": 0x00F6, "oslash": 0x00F8,
        "ugrave": 0x00F9, "uacute": 0x00FA, "ucirc": 0x00FB, "uuml": 0x00FC,
        "yacute": 0x00FD, "thorn": 0x00FE, "yuml": 0x00FF,
        "Agrave": 0x00C0, "Aacute": 0x00C1, "Acirc": 0x00C2, "Atilde": 0x00C3,
        "Auml": 0x00C4, "Aring": 0x00C5, "AElig": 0x00C6, "Ccedil": 0x00C7,
        "Egrave": 0x00C8, "Eacute": 0x00C9, "Ecirc": 0x00CA, "Euml": 0x00CB,
        "Igrave": 0x00CC, "Iacute": 0x00CD, "Icirc": 0x00CE, "Iuml": 0x00CF,
        "ETH": 0x00D0, "Ntilde": 0x00D1, "Ograve": 0x00D2, "Oacute": 0x00D3,
        "Ocirc": 0x00D4, "Otilde": 0x00D5, "Ouml": 0x00D6, "Oslash": 0x00D8,
        "Ugrave": 0x00D9, "Uacute": 0x00DA, "Ucirc": 0x00DB, "Uuml": 0x00DC,
        "Yacute": 0x00DD, "THORN": 0x00DE,
    ]

    /// One pass over the text, replacing only what is genuinely a reference. The name is
    /// capped at 32 characters (the longest real entity is 31) so a bare `&` followed by
    /// prose cannot swallow the rest of the line.
    private static let entityRegex = try? NSRegularExpression(
        pattern: "&(#x[0-9a-fA-F]{1,6}|#[0-9]{1,7}|[a-zA-Z][a-zA-Z0-9]{1,31});"
    )

    static func decodeEntities(_ raw: String) -> String {
        guard raw.contains("&"), let regex = entityRegex else { return raw }
        let text = raw as NSString
        var out = ""
        var cursor = 0
        regex.enumerateMatches(in: raw, range: NSRange(location: 0, length: text.length)) { match, _, _ in
            guard let match else { return }
            let replaced = decodeBody(text.substring(with: match.range(at: 1))) ?? text.substring(with: match.range)
            out += text.substring(with: NSRange(location: cursor, length: match.range.location - cursor))
            out += replaced
            cursor = match.range.location + match.range.length
        }
        if cursor == 0 { return raw }
        out += text.substring(from: cursor)
        return out
    }

    private static func decodeBody(_ body: String) -> String? {
        if body.hasPrefix("#x") || body.hasPrefix("#X") {
            guard let value = UInt32(body.dropFirst(2), radix: 16) else { return nil }
            return emit(value)
        }
        if body.hasPrefix("#") {
            guard let value = UInt32(body.dropFirst()), value <= 0x10FFFF else { return nil }
            return emit(value)
        }
        guard let value = named[body] else { return nil }
        return emit(value)
    }

    /// A surrogate code point is emitted as a bare UTF-16 unit rather than dropped:
    /// pages split an emoji into two references, and the pair only becomes the character
    /// if both halves reach the string.
    private static func emit(_ codePoint: UInt32) -> String? {
        if (0xD800...0xDFFF).contains(codePoint) {
            return String(utf16CodeUnits: [UInt16(codePoint)], count: 1)
        }
        guard let scalar = UnicodeScalar(codePoint) else { return nil }
        return String(scalar)
    }
}
