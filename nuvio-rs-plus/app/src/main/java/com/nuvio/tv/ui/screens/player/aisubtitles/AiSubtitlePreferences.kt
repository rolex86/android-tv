package com.nuvio.tv.ui.screens.player.aisubtitles

import android.content.Context
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

internal object AiSubtitlePreferences {
    private const val PREFS = "nuvio_rs_plus_ai_subtitles"
    private const val KEY_ENABLED = "enabled"
    private const val KEY_BACKEND_URL = "backend_url"
    private const val KEY_API_TOKEN = "api_token"

    const val DEFAULT_BACKEND_URL = "http://192.168.0.109:8787"
    const val TARGET_LANGUAGE = "cs"

    private var loaded = false
    private val _enabled = MutableStateFlow(true)
    private val _backendUrl = MutableStateFlow(DEFAULT_BACKEND_URL)
    private val _apiToken = MutableStateFlow("")

    val enabled: StateFlow<Boolean> = _enabled.asStateFlow()
    val backendUrl: StateFlow<String> = _backendUrl.asStateFlow()
    val apiToken: StateFlow<String> = _apiToken.asStateFlow()

    @Synchronized
    fun ensureLoaded(context: Context) {
        if (loaded) return
        val preferences = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        _enabled.value = preferences.getBoolean(KEY_ENABLED, true)
        _backendUrl.value = preferences.getString(KEY_BACKEND_URL, DEFAULT_BACKEND_URL)
            ?.trim()?.ifBlank { DEFAULT_BACKEND_URL } ?: DEFAULT_BACKEND_URL
        _apiToken.value = preferences.getString(KEY_API_TOKEN, "").orEmpty().trim()
        loaded = true
    }

    fun setEnabled(context: Context, value: Boolean) {
        ensureLoaded(context)
        _enabled.value = value
        prefs(context).edit().putBoolean(KEY_ENABLED, value).apply()
    }

    fun setBackendUrl(context: Context, value: String) {
        ensureLoaded(context)
        val normalized = value.trim().trimEnd('/')
        _backendUrl.value = normalized
        prefs(context).edit().putString(KEY_BACKEND_URL, normalized).apply()
    }

    fun setApiToken(context: Context, value: String) {
        ensureLoaded(context)
        val normalized = value.trim()
        _apiToken.value = normalized
        prefs(context).edit().putString(KEY_API_TOKEN, normalized).apply()
    }

    private fun prefs(context: Context) =
        context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
