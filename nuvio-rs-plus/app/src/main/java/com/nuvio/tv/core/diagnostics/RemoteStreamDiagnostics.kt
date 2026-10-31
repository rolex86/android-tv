package com.nuvio.tv.core.diagnostics

import com.nuvio.tv.domain.model.Stream
import java.security.MessageDigest

/** Diagnostic metadata only. No stream URLs, cookies, tokens, or raw info hashes. */
object RemoteStreamDiagnostics {
    private fun hash(value: String?): String {
        if (value.isNullOrBlank()) return "-"
        val bytes = MessageDigest.getInstance("SHA-256").digest(value.toByteArray(Charsets.UTF_8))
        return bytes.take(8).joinToString("") { "%02x".format(it.toInt() and 255) }
    }

    private fun label(value: String?, limit: Int = 110): String =
        value.orEmpty().replace(Regex("[\\r\\n\\t]"), " ").take(limit).ifBlank { "-" }

    fun describe(stream: Stream): String {
        val parsed = stream.clientResolve?.stream?.raw?.parsed
        val name = stream.behaviorHints?.filename ?: stream.clientResolve?.filename
            ?: stream.clientResolve?.torrentName ?: stream.name ?: stream.title
        return "id=${hash(stream.stableKey())} addon=${label(stream.addonName, 55)} " +
            "name=${label(name)} title=${label(stream.title)} " +
            "quality=${label(stream.quality, 30)} resolution=${label(parsed?.resolution, 20)} " +
            "codecHint=${label(parsed?.codec, 20)} audioHint=${label(parsed?.audio?.joinToString(","), 35)} " +
            "langHint=${label(parsed?.languages?.joinToString(","), 35)} " +
            "size=${stream.behaviorHints?.videoSize ?: stream.debridCacheStatus?.cachedSize ?: -1} " +
            "fileIdx=${stream.getEffectiveFileIdx() ?: -1} " +
            "sourceType=${if (stream.isTorrent()) "torrent" else if (stream.isDirectDebrid()) "debrid" else if (stream.serverTarget != null) "server" else "url"} " +
            "cached=${stream.debridCacheStatus?.state ?: "unknown"} " +
            "bingeId=${hash(stream.behaviorHints?.bingeGroup)} " +
            "contentId=${hash(stream.getEffectiveInfoHash() ?: stream.getStreamUrl())}"
    }

    fun selected(origin: String, stream: Stream) =
        RemoteDebugConsole.record("STREAM_SELECTED", "origin=$origin ${describe(stream)}")

    fun candidates(streams: List<Stream>, mode: String, selected: Stream?) {
        RemoteDebugConsole.record("STREAM_LIST", "mode=$mode total=${streams.size} selected=${selected?.let { hash(it.stableKey()) } ?: "-"}")
        streams.take(60).forEachIndexed { index, item ->
            RemoteDebugConsole.record("STREAM_CANDIDATE", "index=$index ${describe(item)}")
        }
        if (streams.size > 60) RemoteDebugConsole.record("STREAM_LIST", "remaining=${streams.size - 60} omitted")
    }
}
