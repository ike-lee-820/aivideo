package com.ikelee.aivideo

import android.content.Context

class SettingsRepo(ctx: Context) {
    private val sp = ctx.getSharedPreferences("aivideo_settings", Context.MODE_PRIVATE)

    fun load(): AppSettings = AppSettings(
        proxyBase = sp.getString("proxyBase", null) ?: "https://api.ocd.ccwu.cc",
        apiKey = sp.getString("apiKey", null)
            ?: "d2796e4995984341be515ea937df0fff.bIRP2Zxo2j6UwQ70",
        model = sp.getString("model", null) ?: "cogvideox-flash"
    )

    fun save(s: AppSettings) {
        sp.edit()
            .putString("proxyBase", s.proxyBase)
            .putString("apiKey", s.apiKey)
            .putString("model", s.model)
            .apply()
    }
}
