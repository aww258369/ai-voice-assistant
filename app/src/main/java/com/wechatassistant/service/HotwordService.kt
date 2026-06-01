package com.wechatassistant.service

import android.app.*
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import androidx.core.app.NotificationCompat
import com.wechatassistant.voice.VoskEngine

class HotwordService : Service() {

    private val handler = Handler(Looper.getMainLooper())
    private var voskEngine: VoskEngine? = null
    private var isRunning = false

    private val toneGen = ToneGenerator(AudioManager.STREAM_NOTIFICATION, 60)
    private lateinit var vibrator: Vibrator

    private val voskListener = object : VoskEngine.VoskListener {
        override fun onHotwordDetected() {
            sendBroadcast(Intent(BROADCAST_HOTWORD_DETECTED))
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                vibrator.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION") vibrator.vibrate(200)
            }
            toneGen.startTone(ToneGenerator.TONE_PROP_ACK, 200)
            updateNotification("聆听指令中...")
            sendBroadcast(Intent(BROADCAST_LISTENING))
        }

        override fun onListening() {
            updateNotification("聆听指令中...")
            sendBroadcast(Intent(BROADCAST_LISTENING))
        }

        override fun onCommand(text: String) {
            sendBroadcast(Intent(BROADCAST_COMMAND).putExtra("text", text))
        }

        override fun onIdle() {
            updateNotification("聆听「小黑小黑」唤醒")
            sendBroadcast(Intent(BROADCAST_IDLE))
        }

        override fun onError(msg: String) {
            if (isRunning) {
                handler.postDelayed({ voskEngine?.startHotwordDetection(voskListener) }, 500)
            }
        }

        override fun onDownloadProgress(percent: Int) {
            updateNotification("下载模型中... $percent%")
        }
    }

    override fun onCreate() {
        super.onCreate()
        vibrator = getSystemService(VIBRATOR_SERVICE) as Vibrator
        registerReceiver(switchReceiver, IntentFilter(ACTION_SWITCH_HOTWORD))
        registerReceiver(screenReceiver, IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_ON); addAction(Intent.ACTION_SCREEN_OFF)
        })
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) { stopSelf(); return START_NOT_STICKY }
        startForeground(NOTIFICATION_ID, createNotification("正在启动语音引擎..."))
        startHotwordDetection()
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        isRunning = false; voskEngine?.destroy(); voskEngine = null
        try { unregisterReceiver(switchReceiver) } catch (_: Exception) {}
        try { unregisterReceiver(screenReceiver) } catch (_: Exception) {}
        super.onDestroy()
    }

    private fun startHotwordDetection() {
        isRunning = true
        voskEngine = VoskEngine(this)
        if (!voskEngine!!.isModelReady()) {
            updateNotification("首次使用，下载模型中...")
            voskEngine!!.downloadModel { success ->
                if (success) loadAndStart()
                else {
                    updateNotification("模型下载失败，5秒后重试")
                    handler.postDelayed({ if (isRunning) startHotwordDetection() }, 5000)
                }
            }
        } else {
            loadAndStart()
        }
    }

    private fun loadAndStart() {
        voskEngine!!.init { success ->
            if (success && isRunning) {
                sendBroadcast(Intent(BROADCAST_IDLE))
                voskEngine!!.startHotwordDetection(voskListener)
            } else if (isRunning) {
                updateNotification("语音引擎加载失败")
            }
        }
    }

    private fun switchToHotwordMode() {
        voskEngine?.switchToHotword()
        updateNotification("聆听「小黑小黑」唤醒")
        sendBroadcast(Intent(BROADCAST_IDLE))
    }

    private val switchReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == ACTION_SWITCH_HOTWORD) switchToHotwordMode()
        }
    }

    private fun createNotification(text: String): Notification {
        val channelId = "hotword_channel"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val ch = NotificationChannel(channelId, "语音唤醒", NotificationManager.IMPORTANCE_LOW)
                .apply { setSound(null, null) }
            (getSystemService(NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(ch)
        }
        val pi = PendingIntent.getActivity(this, 0,
            packageManager.getLaunchIntentForPackage(packageName),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        return NotificationCompat.Builder(this, channelId)
            .setContentTitle("AI 语音助手").setContentText(text)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now).setContentIntent(pi)
            .setOngoing(true).setPriority(NotificationCompat.PRIORITY_LOW).build()
    }

    private fun updateNotification(text: String) {
        (getSystemService(NOTIFICATION_SERVICE) as NotificationManager)
            .notify(NOTIFICATION_ID, createNotification(text))
    }

    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) switchToHotwordMode()
        }
    }

    companion object {
        private const val TAG = "HotwordService"; private const val NOTIFICATION_ID = 1002
        const val ACTION_STOP = "com.wechatassistant.STOP_HOTWORD"
        const val ACTION_SWITCH_HOTWORD = "com.wechatassistant.SWITCH_HOTWORD"
        const val BROADCAST_HOTWORD_DETECTED = "com.wechatassistant.HOTWORD_DETECTED"
        const val BROADCAST_LISTENING = "com.wechatassistant.LISTENING"
        const val BROADCAST_COMMAND = "com.wechatassistant.COMMAND"
        const val BROADCAST_IDLE = "com.wechatassistant.IDLE"
        const val BROADCAST_TIMEOUT = "com.wechatassistant.TIMEOUT"
        fun start(c: Context) {
            val i = Intent(c, HotwordService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) c.startForegroundService(i) else c.startService(i)
        }
        fun stop(c: Context) { c.stopService(Intent(c, HotwordService::class.java)) }
    }
}
