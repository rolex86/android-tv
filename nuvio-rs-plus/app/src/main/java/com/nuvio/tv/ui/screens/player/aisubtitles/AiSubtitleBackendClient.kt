package com.nuvio.tv.ui.screens.player.aisubtitles

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.nio.charset.StandardCharsets
import java.util.concurrent.TimeUnit
import kotlin.coroutines.coroutineContext

internal class AiSubtitleBackendClient(
    private val httpClient: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .build(),
) {
    @Volatile private var activeJobId: String? = null

    suspend fun translate(
        backendUrl: String,
        apiToken: String,
        job: AiSubtitleJob,
        onProgress: (Int) -> Unit,
    ): AiSubtitleResult {
        val base = validateBaseUrl(backendUrl)
        validateToken(apiToken)
        val requestJson = JSONObject().apply {
            put("subtitleText", job.subtitleText)
            put("sourceFormat", job.sourceFormat)
            putNullable("sourceLanguage", job.sourceLanguage)
            put("targetLanguage", job.targetLanguage)
            putNullable("title", job.title)
            putNullable("contentType", job.contentType)
            putNullable("contentId", job.contentId)
            putNullable("season", job.season)
            putNullable("episode", job.episode)
            put("client", "nuvio-rs-plus")
            put("clientVersion", job.clientVersion)
        }

        var payload = execute(
            requestBuilder(base + "/v1/translations", apiToken)
                .post(requestJson.toString().toRequestBody(JSON))
                .build(),
            initial = true,
        )
        val deadline = System.currentTimeMillis() + MAX_POLL_DURATION_MS
        var lastProgress = -1

        while (true) {
            coroutineContext.ensureActive()
            when (payload.optString("status")) {
                "ready" -> {
                    activeJobId = null
                    return parseReady(payload, job.targetLanguage)
                }
                "failed" -> {
                    val failure = if (payload.optString("errorCode") == "TRANSLATION_TIMEOUT") {
                        AiSubtitleFailure.TIMEOUT
                    } else {
                        AiSubtitleFailure.TRANSLATION_FAILED
                    }
                    throw AiSubtitleException(failure, payload.optString("message").takeIf { it.isNotBlank() })
                }
                "cancelled" -> throw CancellationException("AI subtitle translation cancelled")
                "pending" -> Unit
                else -> throw AiSubtitleException(AiSubtitleFailure.TRANSLATION_FAILED, "Unexpected translation status")
            }

            val jobId = payload.optString("jobId").trim()
            if (!JOB_ID.matches(jobId) || System.currentTimeMillis() >= deadline) {
                throw AiSubtitleException(AiSubtitleFailure.TIMEOUT)
            }
            activeJobId = jobId
            val progress = payload.optInt("progress", 0).coerceIn(0, 100)
            if (progress != lastProgress) {
                lastProgress = progress
                onProgress(progress)
            }
            delay(POLL_INTERVAL_MS)
            payload = execute(
                requestBuilder(base + "/v1/translations/" + jobId, apiToken).get().build(),
                initial = false,
            )
        }
    }

    suspend fun cancelActive(backendUrl: String, apiToken: String) {
        val jobId = activeJobId ?: return
        val base = runCatching { validateBaseUrl(backendUrl) }.getOrNull() ?: return
        withContext(Dispatchers.IO) {
            runCatching {
                httpClient.newCall(
                    requestBuilder(base + "/v1/translations/" + jobId, apiToken).delete().build()
                ).execute().close()
            }
        }
        activeJobId = null
    }

    private suspend fun execute(request: Request, initial: Boolean): JSONObject = withContext(Dispatchers.IO) {
        try {
            httpClient.newCall(request).execute().use { response ->
                mapHttpFailure(response.code, initial)?.let { throw AiSubtitleException(it) }
                val body = response.body ?: throw AiSubtitleException(AiSubtitleFailure.TRANSLATION_FAILED)
                if (body.contentLength() > MAX_RESPONSE_BYTES) throw AiSubtitleException(AiSubtitleFailure.TOO_LARGE)
                val bytes = body.bytes()
                if (bytes.size > MAX_RESPONSE_BYTES) throw AiSubtitleException(AiSubtitleFailure.TOO_LARGE)
                JSONObject(bytes.toString(StandardCharsets.UTF_8))
            }
        } catch (cancel: CancellationException) {
            throw cancel
        } catch (known: AiSubtitleException) {
            throw known
        } catch (error: Exception) {
            throw AiSubtitleException(AiSubtitleFailure.UNAVAILABLE, error.message, error)
        }
    }

    private fun requestBuilder(url: String, apiToken: String): Request.Builder =
        Request.Builder().url(url).header("Accept", "application/json").apply {
            if (apiToken.isNotBlank()) header("Authorization", "Bearer " + apiToken)
        }

    private fun parseReady(payload: JSONObject, targetLanguage: String): AiSubtitleResult {
        val cacheKey = payload.optString("cacheKey").trim()
        val outputFormat = payload.optString("outputFormat").trim()
        val language = payload.optString("language").trim()
        val label = payload.optString("label").trim()
        val subtitleText = payload.optString("subtitleText")
        if (!CACHE_KEY.matches(cacheKey) ||
            outputFormat != "srt" ||
            language != targetLanguage ||
            label.isBlank() || label.length > 120 ||
            subtitleText.isBlank() || !subtitleText.contains(" --> ") ||
            subtitleText.toByteArray(StandardCharsets.UTF_8).size > MAX_SUBTITLE_BYTES
        ) {
            throw AiSubtitleException(AiSubtitleFailure.TRANSLATION_FAILED, "Invalid translation result")
        }
        return AiSubtitleResult(
            cacheKey, outputFormat, language, label, subtitleText,
            payload.optBoolean("cached", false)
        )
    }

    private fun validateBaseUrl(value: String): String {
        val normalized = value.trim().trimEnd('/')
        val parsed = runCatching { Request.Builder().url(normalized).build().url }.getOrNull()
            ?: throw AiSubtitleException(AiSubtitleFailure.INVALID_REQUEST, "Invalid backend URL")
        if (parsed.scheme !in setOf("http", "https") ||
            parsed.username.isNotEmpty() || parsed.password.isNotEmpty() ||
            parsed.query != null || parsed.fragment != null ||
            parsed.encodedPath != "/"
        ) {
            throw AiSubtitleException(AiSubtitleFailure.INVALID_REQUEST, "Unsupported backend URL")
        }
        return parsed.toString().trimEnd('/')
    }

    private fun validateToken(value: String) {
        if (value.length > 4096 || '\r' in value || '\n' in value) {
            throw AiSubtitleException(AiSubtitleFailure.INVALID_REQUEST, "Invalid API token")
        }
    }

    private fun mapHttpFailure(statusCode: Int, initial: Boolean): AiSubtitleFailure? = when {
        statusCode == 401 || statusCode == 403 -> AiSubtitleFailure.UNAUTHORIZED
        statusCode == 413 -> AiSubtitleFailure.TOO_LARGE
        statusCode == 400 || statusCode == 422 -> AiSubtitleFailure.INVALID_REQUEST
        statusCode >= 500 -> AiSubtitleFailure.UNAVAILABLE
        initial && statusCode != 200 && statusCode != 202 -> AiSubtitleFailure.TRANSLATION_FAILED
        !initial && statusCode != 200 -> AiSubtitleFailure.TRANSLATION_FAILED
        else -> null
    }

    private fun JSONObject.putNullable(key: String, value: Any?) {
        put(key, value ?: JSONObject.NULL)
    }

    companion object {
        private val JSON = "application/json; charset=utf-8".toMediaType()
        private val JOB_ID = Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
        private val CACHE_KEY = Regex("[A-Za-z0-9_-]{8,128}")
        private const val POLL_INTERVAL_MS = 2_000L
        private const val MAX_POLL_DURATION_MS = 60L * 60L * 1000L
        private const val MAX_RESPONSE_BYTES = 3 * 1024 * 1024
        private const val MAX_SUBTITLE_BYTES = 2 * 1024 * 1024
    }
}
