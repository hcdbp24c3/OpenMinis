package com.openminis.app.tools

import okhttp3.ResponseBody
import okio.Buffer

/**
 * [T-http-body-read] Bounded response-body reads for the tools that pull bytes off the
 * network themselves.
 *
 * This exists because of one bug, in two places: `BufferedSource.readByteArray(n)` —
 * what both tools used — requires EXACTLY `n` bytes and throws
 * `EOFException("End of input")` when the body is shorter. The count came from
 * `body.contentLength()`, which is **-1 whenever OkHttp has decoded a gzip (or any
 * compressed) or chunked body**, so the call asked for the whole 2MB ceiling and died
 * on the first byte past the real end. Every host that compresses — that is, every
 * host, since OkHttp sends `Accept-Encoding: gzip` — failed with an EOF that pointed at
 * the network rather than at the reader. chunked responses failed the same way, and in
 * repo_digest the `contentLength().coerceAtLeast(0)` form silently read ZERO bytes
 * instead of throwing.
 *
 * `BufferedSource.read(sink, byteCount)` is the API that means "up to this many",
 * returning -1 at the real end, so a short body is read to completion and a long one is
 * cut at the ceiling — which is the bound these tools actually wanted.
 */
internal object HttpBodyReader {

    private const val CHUNK_BYTES = 64L * 1024

    /** At most [maxBytes] bytes of [body]. Null body, or an empty stream, yields empty. */
    fun readCapped(body: ResponseBody?, maxBytes: Int): ByteArray {
        if (body == null) return ByteArray(0)
        val max = maxBytes.coerceAtLeast(0).toLong()
        if (max == 0L) return ByteArray(0)
        val sink = Buffer()
        var remaining = max
        val source = body.source()
        while (remaining > 0) {
            val read = source.read(sink, minOf(remaining, CHUNK_BYTES))
            if (read == -1L) break
            remaining -= read
        }
        return sink.readByteArray()
    }
}
