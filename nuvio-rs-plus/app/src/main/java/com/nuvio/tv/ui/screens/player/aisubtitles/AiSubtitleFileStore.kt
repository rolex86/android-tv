package com.nuvio.tv.ui.screens.player.aisubtitles

import android.content.Context
import com.nuvio.tv.domain.model.Subtitle
import java.io.File

internal object AiSubtitleFileStore {
    private const val DIRECTORY = "ai_subtitles"
    private const val MAX_FILES = 20
    private const val MAX_AGE_MS = 30L * 24L * 60L * 60L * 1000L

    fun store(context: Context, result: AiSubtitleResult): Subtitle {
        val directory = File(context.cacheDir, DIRECTORY).also { require(it.isDirectory || it.mkdirs()) }
        val destination = File(directory, result.cacheKey + ".srt")
        val temporary = File(directory, result.cacheKey + ".srt.tmp")
        temporary.writeText(result.subtitleText, Charsets.UTF_8)
        if (destination.exists()) destination.delete()
        require(temporary.renameTo(destination)) { "Could not finalize translated subtitle" }
        prune(directory)

        return Subtitle(
            id = "nuvio-ai:" + result.cacheKey,
            url = destination.toURI().toString(),
            lang = result.language,
            addonName = result.label.ifBlank { "Čeština (AI)" },
            addonLogo = null,
            isStreamProvided = true,
        )
    }

    fun read(subtitle: Subtitle): String? {
        if (!isAiSubtitle(subtitle)) return null
        return runCatching {
            val file = File(java.net.URI(subtitle.url))
            file.takeIf { it.isFile }?.readText(Charsets.UTF_8)
        }.getOrNull()
    }

    private fun prune(directory: File) {
        val cutoff = System.currentTimeMillis() - MAX_AGE_MS
        directory.listFiles { file -> file.extension.equals("srt", ignoreCase = true) }
            ?.sortedByDescending { it.lastModified() }
            ?.forEachIndexed { index, file ->
                if (index >= MAX_FILES || file.lastModified() < cutoff) file.delete()
            }
    }
}

internal fun isAiSubtitle(subtitle: Subtitle): Boolean =
    subtitle.id.startsWith("nuvio-ai:") || subtitle.url.startsWith("file:") &&
        subtitle.addonName.contains("AI", ignoreCase = true)
