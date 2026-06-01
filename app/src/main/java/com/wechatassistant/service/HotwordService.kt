package com.wechatassistant.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.util.Log
import androidx.core.app.NotificationCompat
import java.util.Locale

class HotwordService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var speechRecognizer: SpeechRecognizer? = null
    private var isRunning = false
    private var isHotwordMode = true
    private var wakeCount = 0

    private val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60)
    private lateinit var vibrator: Vibrator

    override fun onCreate() {
        super.onCreate()
        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        val filter = IntentFilter(ACTION_SWITCH_HOTWORD)
        registerReceiver(switchReceiver, filter)

        val screenFilter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON)
            addAction(Intent.ACTION_SCREEN_OFF)
        }
        registerReceiver(screenReceiver, screenFilter)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            stopSelf()
            return START_NOT_STICKY
        }
        startForeground(NOTIFICATION_ID, createNotification("聆听「小黑小黑」唤醒"))
        startHotwordDetection()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopHotwordDetection()
        try { unregisterReceiver(switchReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun startHotwordDetection() {
        isRunning = true
        isHotwordMode = true
        wakeCount = 0
        sendBroadcast(Intent(BROADCAST_IDLE))
        startSpeechRecognition()
    }

    private fun stopHotwordDetection() {
        isRunning = false
        speechRecognizer?.destroy()
        speechRecognizer = null
        handler.removeCallbacksAndMessages(null)
    }

    private fun startSpeechRecognition() {
        if (!isRunning) return

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {}
            override fun onBeginningOfSpeech() {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onEndOfSpeech() {}

            override fun onError(error: Int) {
                handler.postDelayed({
                    if (isRunning) {
                        speechRecognizer?.destroy()
                        speechRecognizer = null
                        startSpeechRecognition()
                    }
                }, 300)
            }

            override fun onPartialResults(partialResults: Bundle?) {
                val matches = partialResults
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty() && isHotwordMode) {
                    checkHotword(matches[0])
                }
            }

            override fun onResults(results: Bundle?) {
                val matches = results
                    ?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    val text = matches[0]
                    if (isHotwordMode) {
                        checkHotword(text)
                        handler.postDelayed({
                            if (isRunning) startSpeechRecognition()
                        }, 100)
                    } else {
                        sendBroadcast(Intent(BROADCAST_COMMAND).putExtra("text", text))
                    }
                } else {
                    if (isRunning) startSpeechRecognition()
                }
            }

            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.CHINESE.toString())
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_PREFERENCE, "zh-CN")
            putExtra(RecognizerIntent.EXTRA_PARTIAL_RESULTS, true)
            putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 3)

            if (isHotwordMode) {
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 1000)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 1500)
            } else {
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 3000)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 2000)
            }
        }
        speechRecognizer?.startListening(intent)
    }

    private fun checkHotword(text: String) {
        val clean = text.replace(" ", "").lowercase(Locale.CHINESE)
        if (clean.contains("小黑")) {
            wakeCount++
            if (wakeCount >= 2) {
                onWakeUp()
            } else {
                handler.removeCallbacks(hotwordTimeoutRunnable)
                handler.postDelayed(hotwordTimeoutRunnable, 2000)
            }
        } else if (wakeCount == 1) {
            wakeCount = 0
            switchToCommandMode()
            sendBroadcast(Intent(BROADCAST_COMMAND).putExtra("text", text))
        }
    }

    private val hotwordTimeoutRunnable = Runnable {
        if (wakeCount == 1) wakeCount = 0
    }

    private fun onWakeUp() {
        wakeCount = 0
        sendBroadcast(Intent(BROADCAST_HOTWORD_DETECTED))

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator.vibrate(
                VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE)
            )
        } else {
            @Suppress("DEPRECATION")
            vibrator.vibrate(200)
        }
        toneGen.startTone(ToneGenerator.TONE_PROP_ACK, 200)

        updateNotification("聆听指令中...")
        sendBroadcast(Intent(BROADCAST_LISTENING))
        switchToCommandMode()
    }

    private fun switchToCommandMode() {
        isHotwordMode = false
        speechRecognizer?.destroy()
        speechRecognizer = null
        handler.postDelayed({
            if (isRunning) startSpeechRecognition()
        }, 200)
        handler.removeCallbacks(commandTimeoutRunnable)
        handler.postDelayed(commandTimeoutRunnable, 8000)
    }

    private val commandTimeoutRunnable = Runnable {
        sendBroadcast(Intent(BROADCAST_TIMEOUT))
        switchToHotwordMode()
    }

    private fun switchToHotwordMode() {
        isHotwordMode = true
        wakeCount = 0
        updateNotification("聆听「小黑小黑」唤醒")
        sendBroadcast(Intent(BROADCAST_IDLE))
        speechRecognizer?.destroy()
        speechRecognizer = null
        handler.postDelayed({
            if (isRunning) startSpeechRecognition()
        }, 200)
    }

    private val switchReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_SWITCH_HOTWORD) {
                handler.removeCallbacks(commandTimeoutRunnable)
                switchToHotwordMode()
            }
        }
    }

    private fun createNotification(text: String): Notification {
        val channelId = "hotword_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId, "语音唤醒", NotificationManager.IMPORTANCE_LOW
            ).apply { setSound(null, null) }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
                .createNotificationChannel(channel)
        }

        val pi = PendingIntent.getActivity(
            this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("AI 语音助手")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentIntent(pi)
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, createNotification(text))
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF && !isHotwordMode) {
                switchToHotwordMode()
            }
        }
    }

    companion object {
        private const val TAG = "HotwordService"
        private const val NOTIFICATION_ID = 1002

        const val ACTION_STOP = "com.wechatassistant.STOP_HOTWORD"
        const val ACTION_SWITCH_HOTWORD = "com.wechatassistant.SWITCH_HOTWORD"
        const val BROADCAST_HOTWORD_DETECTED = "com.wechatassistant.HOTWORD_DETECTED"
        const val BROADCAST_LISTENING = "com.wechatassistant.LISTENING"
        const val BROADCAST_COMMAND = "com.wechatassistant.COMMAND"
        const val BROADCAST_IDLE = "com.wechatassistant.IDLE"
        const val BROADCAST_TIMEOUT = "com.wechatassistant.TIMEOUT"

        fun start(context: Context) {
            val intent = Intent(context, HotwordService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, HotwordService::class.java))
        }
    }
}
