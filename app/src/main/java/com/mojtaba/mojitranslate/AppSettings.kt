package com.mojtaba.mojitranslate

import android.content.Context

object AppSettings {
    const val PREFS = "moji_translate_settings"
    const val MODE = "translation_mode"
    const val MODE_AUTO = "auto"
    const val MODE_ONLINE = "online"
    const val MODE_OFFLINE = "offline"
    const val TEXT_SCALE = "overlay_text_scale"
    const val COVER_OPACITY = "overlay_cover_opacity"
    const val OFFLINE_WIFI_ONLY = "offline_model_wifi_only"
    const val DIAGNOSTICS_ENABLED = "diagnostics_enabled"

    fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
}
