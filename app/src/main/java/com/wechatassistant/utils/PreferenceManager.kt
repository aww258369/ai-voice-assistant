package com.wechatassistant.utils

import android.content.Context
import android.content.SharedPreferences

class PreferenceManager(context: Context) {

    private val prefs: SharedPreferences =
        context.getSharedPreferences("wechat_assistant_prefs", Context.MODE_PRIVATE)

    fun saveApiKey(key: String) {
        prefs.edit().putString("deepseek_api_key", key).apply()
    }

    fun getApiKey(): String = prefs.getString("deepseek_api_key", "") ?: ""

    fun hasApiKey(): Boolean = getApiKey().isNotBlank()
}
