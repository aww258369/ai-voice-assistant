package com.wechatassistant.voice

import android.content.Context
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Handler
import android.os.Looper
import android.util.Log
import org.vosk.*
import org.vosk.android.AndroidLibVosk
import java.io.*
import java.net.URL
import java.util.zip.ZipInputStream

class VoskEngine(private val context: Context) {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var model: Model? = null
    private var recognizer: Recognizer? = null
    private var audioRecord: AudioRecord? = null
    private var isRunning = false
    private var isHotwordMode = true
    private var wakeCount = 0
    private var listener: VoskListener? = null

    companion object {
        private const val TAG = "VoskEngine"
        private const val SAMPLE_RATE = 16000
        private const val MODEL_NAME = "vosk-model-small-cn-0.22"
        private const val MODEL_URL = "https://alphacephei.com/vosk/models/$MODEL_NAME.zip"
        private const val HOTWORD = "小黑"
    }

    interface VoskListener {
        fun onHotwordDetected()
        fun onListening()
        fun onCommand(text: String)
        fun onIdle()
        fun onError(msg: String)
        fun onDownloadProgress(percent: Int)
    }

    fun isModelReady(): Boolean {
        return File(context.filesDir, MODEL_NAME).exists()
    }

    fun downloadModel(callback: (Boolean) -> Unit) {
        Thread {
            try {
                val modelDir = File(context.filesDir, MODEL_NAME)
                modelDir.mkdirs()
                val zipFile = File(context.cacheDir, "$MODEL_NAME.zip")

                Log.i(TAG, "开始下载模型: $MODEL_URL")

                // 下载
                val connection = URL(MODEL_URL).openConnection()
                connection.connect()
                val totalSize = connection.contentLengthLong
                val input = connection.getInputStream()
                val output = FileOutputStream(zipFile)
                val buffer = ByteArray(8192)
                var bytesRead: Int
                var totalRead = 0L
                var lastPercent = -1

                while (input.read(buffer).also { bytesRead = it } != -1) {
                    output.write(buffer, 0, bytesRead)
                    totalRead += bytesRead
                    if (totalSize > 0) {
                        val percent = (totalRead * 100 / totalSize).toInt()
                        if (percent != lastPercent) {
                            lastPercent = percent
                            mainHandler.post { listener?.onDownloadProgress(percent) }
                        }
                    }
                }
                output.close()
                input.close()

                // 解压
                Log.i(TAG, "下载完成，开始解压...")
                unzip(zipFile, modelDir.parentFile!!)
                zipFile.delete()

                mainHandler.post {
                    Log.i(TAG, "模型解压完成")
                    callback(true)
                }
            } catch (e: Exception) {
                Log.e(TAG, "模型下载失败: ${e.message}")
                mainHandler.post { callback(false) }
            }
        }.start()
    }

    private fun unzip(zipFile: File, destDir: File) {
        val zis = ZipInputStream(FileInputStream(zipFile))
        var entry = zis.nextEntry
        while (entry != null) {
            val file = File(destDir, entry.name)
            if (entry.isDirectory) {
                file.mkdirs()
            } else {
                file.parentFile?.mkdirs()
                FileOutputStream(file).use { fos ->
                    zis.copyTo(fos)
                }
            }
            zis.closeEntry()
            entry = zis.nextEntry
        }
        zis.close()
    }

    fun init(callback: (Boolean) -> Unit) {
        AndroidLibVosk.init(context)
        val modelPath = File(context.filesDir, MODEL_NAME).absolutePath
        try {
            model = Model(modelPath)
            Log.i(TAG, "Vosk 模型加载成功")
            callback(true)
        } catch (e: Exception) {
            Log.e(TAG, "模型加载失败: ${e.message}")
            callback(false)
        }
    }

    fun startHotwordDetection(listener: VoskListener) {
        this.listener = listener
        isHotwordMode = true
        wakeCount = 0
        listener.onIdle()
        startAudioLoop()
    }

    fun startCommandMode(listener: VoskListener) {
        this.listener = listener
        isHotwordMode = false
        listener.onListening()
        stopAudio()
        startAudioLoop()
    }

    fun stop() {
        isRunning = false
        stopAudio()
        recognizer?.close()
        recognizer = null
    }

    fun destroy() {
        stop()
        model?.close()
        model = null
    }

    fun switchToHotword() {
        isHotwordMode = true
        wakeCount = 0
        recognizer?.reset()
        listener?.onIdle()
    }

    fun switchToCommandAfterTimeout() {
        // 切回热词模式
        switchToHotword()
    }

    private fun startAudioLoop() {
        if (isRunning) return
        isRunning = true

        val minBuffer = AudioRecord.getMinBufferSize(
            SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
        )

        try {
            recognizer = Recognizer(model, SAMPLE_RATE.toFloat())
        } catch (e: Exception) {
            Log.e(TAG, "创建 Recognizer 失败: ${e.message}")
            mainHandler.post { listener?.onError("语音引擎启动失败") }
            isRunning = false
            return
        }

        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC, SAMPLE_RATE,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT,
            minBuffer * 2
        )

        if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
            mainHandler.post { listener?.onError("麦克风初始化失败") }
            isRunning = false
            return
        }

        audioRecord?.startRecording()

        Thread {
            val buffer = ByteArray(minBuffer)
            var commandTimeout = 0L

            while (isRunning) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: -1
                if (read > 0) {
                    if (recognizer?.acceptWaveform(buffer, read) == true) {
                        val result = recognizer?.result ?: "{}"
                        handleResult(result, isFinal = true)
                    } else {
                        val partial = recognizer?.partialResult ?: "{}"
                        handleResult(partial, isFinal = false)
                    }

                    // 指令模式下超时检测
                    if (!isHotwordMode) {
                        if (commandTimeout == 0L) {
                            commandTimeout = System.currentTimeMillis()
                        } else if (System.currentTimeMillis() - commandTimeout > 8000) {
                            mainHandler.post {
                                listener?.onError("指令超时")
                                switchToHotword()
                            }
                            isRunning = false
                        }
                    } else {
                        commandTimeout = 0L
                    }
                }
            }

            // 清理
            try {
                audioRecord?.stop()
                audioRecord?.release()
                audioRecord = null
            } catch (_: Exception) {}
        }.start()
    }

    private fun stopAudio() {
        isRunning = false
        try {
            audioRecord?.stop()
            audioRecord?.release()
            audioRecord = null
        } catch (_: Exception) {}
    }

    private fun handleResult(jsonStr: String, isFinal: Boolean) {
        try {
            // 解析 JSON 获取 text 字段
            val text = extractText(jsonStr)

            if (isHotwordMode) {
                if (isFinal && text.isNotEmpty()) {
                    checkHotword(text)
                } else if (!isFinal && text.isNotEmpty()) {
                    // 部分结果也检查热词，提高响应速度
                    checkHotword(text)
                }
            } else {
                if (isFinal && text.isNotEmpty()) {
                    mainHandler.post {
                        listener?.onCommand(text)
                    }
                } else if (!isFinal && text.isNotEmpty()) {
                    // 部分结果也给到 UI 显示
                    Log.d(TAG, "部分识别: $text")
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析结果失败: ${e.message}")
        }
    }

    private fun extractText(jsonStr: String): String {
        // Vosk JSON: {"text": "你好", "partial": "你好..."}
        val textKey = if (jsonStr.contains("\"text\"")) "text" else "partial"
        val start = jsonStr.indexOf("\"$textKey\":\"")
        if (start == -1) return ""
        val valueStart = start + textKey.length + 4
        val valueEnd = jsonStr.indexOf('"', valueStart)
        return if (valueEnd > valueStart) jsonStr.substring(valueStart, valueEnd) else ""
    }

    private fun checkHotword(text: String) {
        val clean = text.replace(" ", "")
        if (clean.contains(HOTWORD)) {
            wakeCount++
            if (wakeCount >= 2) {
                wakeCount = 0
                mainHandler.post {
                    listener?.onHotwordDetected()
                }
            } else {
                // 1 秒内等第二次
                Handler(Looper.getMainLooper()).postDelayed({
                    if (wakeCount == 1) wakeCount = 0
                }, 1500)
            }
        } else if (wakeCount == 1) {
            // 一次"小黑"后直接跟指令
            wakeCount = 0
            mainHandler.post {
                listener?.onHotwordDetected()
                isHotwordMode = false
                listener?.onListening()
                recognizer?.reset()
            }
        }
    }

    private fun Recognizer.reset() {
        // Vosk doesn't have a reset method directly, create a new one
        try {
            this.close()
            val newRec = Recognizer(model, SAMPLE_RATE.toFloat())
            recognizer = newRec
        } catch (e: Exception) {
            Log.e(TAG, "重置识别器失败: ${e.message}")
        }
    }
}
