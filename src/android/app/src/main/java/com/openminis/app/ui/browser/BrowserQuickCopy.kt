package com.openminis.app.ui.browser

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Build
import android.widget.Toast

/**
 * [T-browser-quick-copy] What the in-app browser's copy button puts on the
 * clipboard.
 *
 * The button copies the page LINK. The other kinds are here so a later
 * "copy title + link" or "copy page text" entry only has to pass a different
 * [Kind] (and, for text, the text) — the clipboard and confirmation code is
 * shared.
 */
object BrowserQuickCopy {

    enum class Kind { LINK, MARKDOWN_LINK, TEXT }

    /** Longest text handed to the clipboard; a binder transaction caps near 1 MB. */
    const val MAX_CHARS = 100_000

    /** A page worth copying: not blank and not the empty tab. */
    fun canCopy(url: String?): Boolean {
        if (url == null) return false
        val u = url.trim()
        if (u.isEmpty()) return false
        if (u.equals("about:blank", ignoreCase = true)) return false
        return true
    }

    /**
     * The exact string to copy, or null when there is nothing to copy.
     * [text] is only read for [Kind.TEXT].
     */
    fun payload(kind: Kind, url: String?, title: String?, text: String? = null): String? {
        val out: String? = when (kind) {
            Kind.LINK -> if (canCopy(url)) url!!.trim() else null
            Kind.MARKDOWN_LINK -> if (canCopy(url)) markdownLink(title, url!!.trim()) else null
            Kind.TEXT -> text?.takeIf { it.isNotBlank() }
        }
        if (out == null) return null
        return if (out.length > MAX_CHARS) out.substring(0, MAX_CHARS) else out
    }

    /** `[title](url)`; a blank title falls back to the bare link. */
    fun markdownLink(title: String?, url: String): String {
        val t = (title ?: "").replace('\n', ' ').replace('\r', ' ').trim()
        if (t.isEmpty()) return url
        val safeTitle = t.replace("\\", "\\\\").replace("[", "\\[").replace("]", "\\]")
        val safeUrl = url.replace(" ", "%20").replace("(", "%28").replace(")", "%29")
        return "[$safeTitle]($safeUrl)"
    }

    /** Short confirmation shown after a copy. */
    fun confirmation(kind: Kind): String = when (kind) {
        Kind.LINK -> "Link copied"
        Kind.MARKDOWN_LINK -> "Markdown link copied"
        Kind.TEXT -> "Text copied"
    }

    /**
     * Copy and confirm. Returns false when there was nothing to copy or the
     * clipboard refused. Android 13+ shows its own clipboard preview, so the
     * toast is only raised on older versions.
     */
    fun copy(context: Context, kind: Kind, url: String?, title: String?, text: String? = null): Boolean {
        val value: String = payload(kind, url, title, text) ?: return false
        return try {
            val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
                ?: return false
            cm.setPrimaryClip(ClipData.newPlainText("browser", value))
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) {
                Toast.makeText(context, confirmation(kind), Toast.LENGTH_SHORT).show()
            }
            true
        } catch (e: Exception) {
            false
        }
    }
}
