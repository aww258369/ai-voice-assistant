package com.wechatassistant.voice

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log

/**
 * 手动语音识别（按按钮 → 说话 → 识别）
 * 使用 Vosk 离线引擎，不依赖 Google 服务
 */
class VoiceManager(private val context: Context) {

    private var isListening = false
    private var listener: VoiceListener? = null
    private var voskEngine: VoskEngine? = null
    private val handler = Handler(Looper.getMainLooper())

    interface VoiceListener {
        fun onResult(text: String)
        fun onError(message: String)
        fun onListening()
    }

    fun setListener(l: VoiceListener) { this.listener = l }

    fun startListening() {
        if (isListening) return

        voskEngine = VoskEngine(context)
        if (!voskEngine!!.isModelReady()) {
            listener?.onError("语音模型未下载，请先开启热词唤醒")
            return
        }

        voskEngine!!.init { success ->
            if (success) {
                isListening = true
                listener?.onListening()

                voskEngine!!.startCommandMode(object : VoskEngine.VoskListener {
                    override fun onHotwordDetected() {}
                    override fun onListening() { listener?.onListening() }
                    override fun onCommand(text: String) {
                        isListening = false
                        listener?.onResult(text)
                        voskEngine?.destroy()
                        voskEngine = null
                    }
                    override fun onIdle() {}
                    override fun onError(msg: String) {
                        isListening = false
                        listener?.onError(msg)
                        voskEngine?.destroy()
                        voskEngine = null
                    }
                    override fun onDownloadProgress(percent: Int) {}
                })

                handler.postDelayed({
                    if (isListening) {
                        isListening = false
                        listener?.onError("听写超时")
                        voskEngine?.destroy()
                        voskEngine = null
                    }
                }, 8000)

            } else {
                listener?.onError("语音引擎加载失败")
            }
        }
    }

    fun stopListening() {
        isListening = false
        voskEngine?.destroy()
        voskEngine = null
    }

    fun destroy() { stopListening() }

    companion object {
        fun isVoskModelReady(context: Context): Boolean {
            return VoskEngine(context).isModelReady()
        }
    }
}
