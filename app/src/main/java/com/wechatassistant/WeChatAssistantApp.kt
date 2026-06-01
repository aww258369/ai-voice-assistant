package com.wechatassistant

import android.app.Application
import com.wechatassistant.utils.PreferenceManager

class WeChatAssistantApp : Application() {

    lateinit var preferenceManager: PreferenceManager
        private set

    override fun onCreate() {
        super.onCreate()
        instance = this
        preferenceManager = PreferenceManager(this)
    }

    companion object {
        lateinit var instance: WeChatAssistantApp
            private set
    }
}
