package com.wechatassistant.ui

import android.Manifest
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.speech.SpeechRecognizer
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.google.android.material.snackbar.Snackbar
import com.wechatassistant.R
import com.wechatassistant.WeChatAssistantApp
import com.wechatassistant.ai.DeepSeekClient
import com.wechatassistant.databinding.ActivityMainBinding
import com.wechatassistant.service.AssistantService
import com.wechatassistant.service.HotwordService
import com.wechatassistant.voice.VoiceManager

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private lateinit var voiceManager: VoiceManager
    private lateinit var deepSeekClient: DeepSeekClient

    private var isProcessing = false
    private var isHotwordActive = false
    private var currentTaskActions: List<DeepSeekClient.ActionItem> = emptyList()
    private var currentActionIndex = 0
    private val mainHandler = Handler(Looper.getMainLooper())

    // ===== 接收热词服务的广播 =====
    private val hotwordReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent == null) return
            when (intent.action) {
                HotwordService.BROADCAST_HOTWORD_DETECTED -> {
                    binding.statusText.text = "🔥 小黑小黑！"
                    binding.aiResponseText.visibility = android.view.View.VISIBLE
                    binding.aiResponseText.text = "👂 请说指令..."
                    Toast.makeText(this@MainActivity, "🔥 我在！请说指令", Toast.LENGTH_SHORT).show()
                }
                HotwordService.BROADCAST_LISTENING -> {
                    binding.statusText.text = "👂 聆听指令中..."
                }
                HotwordService.BROADCAST_COMMAND -> {
                    val text = intent.getStringExtra("text") ?: return
                    if (!isProcessing) {
                        binding.recognizedText.text = text
                        processVoiceCommand(text, fromHotword = true)
                    }
                }
                HotwordService.BROADCAST_IDLE -> {
                    binding.statusText.text = "🎧 说「小黑小黑」唤醒"
                }
                HotwordService.BROADCAST_TIMEOUT -> {
                    binding.statusText.text = "⏰ 指令超时，说「小黑小黑」唤醒"
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // 注册热词广播接收器
        val filter = IntentFilter().apply {
            addAction(HotwordService.BROADCAST_HOTWORD_DETECTED)
            addAction(HotwordService.BROADCAST_LISTENING)
            addAction(HotwordService.BROADCAST_COMMAND)
            addAction(HotwordService.BROADCAST_IDLE)
            addAction(HotwordService.BROADCAST_TIMEOUT)
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(hotwordReceiver, filter, RECEIVER_EXPORTED)
        } else {
            registerReceiver(hotwordReceiver, filter)
        }

        // 初始化手动语音识别
        voiceManager = VoiceManager(this)
        voiceManager.setListener(object : VoiceManager.VoiceListener {
            override fun onResult(text: String) {
                runOnUiThread {
                    binding.recognizedText.text = text
                    processVoiceCommand(text, fromHotword = false)
                }
            }
            override fun onError(message: String) {
                runOnUiThread {
                    binding.statusText.text = "识别失败: $message"
                    binding.voiceIcon.text = "🎤"
                }
            }
            override fun onListening() {
                runOnUiThread {
                    binding.statusText.text = "👂 聆听中..."
                    binding.voiceIcon.text = "🔊"
                }
            }
        })

        // 加载 API Key
        val prefs = WeChatAssistantApp.instance.preferenceManager
        if (prefs.hasApiKey()) {
            binding.apiKeyInput.setText(prefs.getApiKey())
        }
        deepSeekClient = DeepSeekClient(prefs.getApiKey())

        // ===== 按钮事件 =====

        binding.saveApiKeyBtn.setOnClickListener {
            val key = binding.apiKeyInput.text.toString().trim()
            if (key.isNotBlank()) {
                prefs.saveApiKey(key)
                deepSeekClient = DeepSeekClient(key)
                Toast.makeText(this, "API Key 已保存", Toast.LENGTH_SHORT).show()
            }
        }

        binding.voiceButtonCard.setOnClickListener {
            if (isProcessing) {
                Toast.makeText(this, "正在处理上一个指令", Toast.LENGTH_SHORT).show()
                return@setOnClickListener
            }
            checkPermissionsAndListen()
        }

        binding.openAccessibilityBtn.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        }

        binding.hotwordToggle.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startHotwordDetection()
            } else {
                stopHotwordDetection()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        updateServiceStatus()
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(hotwordReceiver) } catch (_: Exception) {}
        voiceManager.destroy()
    }

    // ===== 热词唤醒 =====

    private fun startHotwordDetection() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO_PERMISSION
            )
            binding.hotwordToggle.isChecked = false
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATION_PERMISSION
                )
            }
        }

        HotwordService.start(this)
        isHotwordActive = true
        binding.statusText.text = "🎧 说「小黑小黑」唤醒"
    }

    private fun stopHotwordDetection() {
        isHotwordActive = false
        HotwordService.stop(this)
        updateServiceStatus()
    }

    private fun switchBackToHotword() {
        sendBroadcast(Intent(HotwordService.ACTION_SWITCH_HOTWORD))
        binding.statusText.text = "🎧 说「小黑小黑」唤醒"
    }

    // ===== 状态 =====

    private fun updateServiceStatus() {
        if (isHotwordActive) return
        binding.statusText.text = if (isAccessibilityServiceEnabled()) {
            "✅ 辅助功能已开启"
        } else {
            "⚠️ 辅助功能未开启"
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = "$packageName/com.wechatassistant.service.AssistantService"
        return try {
            val enabled = Settings.Secure.getString(
                contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
            )
            enabled?.contains(serviceName) == true
        } catch (_: Exception) { false }
    }

    // ===== 手动麦克风 =====

    private fun checkPermissionsAndListen() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            ActivityCompat.requestPermissions(
                this, arrayOf(Manifest.permission.RECORD_AUDIO), REQUEST_AUDIO_PERMISSION
            )
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            if (!Settings.canDrawOverlays(this)) {
                Snackbar.make(binding.root, "需要悬浮窗权限", Snackbar.LENGTH_LONG)
                    .setAction("去开启") {
                        startActivity(Intent(
                            Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                            android.net.Uri.parse("package:$packageName")
                        ))
                    }.show()
            }
        }
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "不支持语音识别", Toast.LENGTH_LONG).show()
            return
        }
        voiceManager.startListening()
    }

    // ===== 指令处理 =====

    private fun processVoiceCommand(text: String, fromHotword: Boolean) {
        isProcessing = true
        binding.voiceIcon.text = "⏳"
        binding.statusText.text = "处理中..."
        binding.aiResponseText.visibility = android.view.View.VISIBLE
        binding.aiResponseText.text = "🤖 正在思考..."

        deepSeekClient.understand(text, object : DeepSeekClient.Callback {
            override fun onSuccess(actions: List<DeepSeekClient.ActionItem>) {
                runOnUiThread {
                    if (actions.isEmpty()) {
                        binding.aiResponseText.text = "😅 没理解，换种说法"
                        finishProcessing(fromHotword)
                        return@runOnUiThread
                    }
                    binding.aiResponseText.text = "解析到 ${actions.size} 个操作"
                    currentTaskActions = actions
                    currentActionIndex = 0
                    executeNextAction(fromHotword)
                }
            }

            override fun onError(message: String) {
                runOnUiThread {
                    binding.aiResponseText.text = "❌ $message"
                    finishProcessing(fromHotword)
                }
            }
        })
    }

    private fun executeNextAction(fromHotword: Boolean) {
        if (currentActionIndex >= currentTaskActions.size) {
            binding.aiResponseText.text = "✅ 执行完成！"
            finishProcessing(fromHotword)
            return
        }

        val action = currentTaskActions[currentActionIndex]
        binding.aiResponseText.text =
            "[${currentActionIndex + 1}/${currentTaskActions.size}] ${action.action}"
        binding.voiceIcon.text = "⚡"

        val service = AssistantService.instance
        if (service != null) {
            service.executeAction(action) { success ->
                runOnUiThread {
                    currentActionIndex++
                    mainHandler.postDelayed({ executeNextAction(fromHotword) }, 500)
                }
            }
        } else {
            Snackbar.make(binding.root, "请开启辅助功能", Snackbar.LENGTH_LONG)
                .setAction("去开启") {
                    startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                }.show()
            binding.aiResponseText.text = "❌ 需要辅助功能"
            finishProcessing(fromHotword)
        }
    }

    private fun finishProcessing(fromHotword: Boolean) {
        isProcessing = false
        binding.voiceIcon.text = "🎤"
        if (fromHotword && isHotwordActive) {
            switchBackToHotword()
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        when (requestCode) {
            REQUEST_AUDIO_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    if (binding.hotwordToggle.isChecked) startHotwordDetection()
                    else checkPermissionsAndListen()
                } else {
                    Toast.makeText(this, "需要录音权限", Toast.LENGTH_LONG).show()
                }
            }
            REQUEST_NOTIFICATION_PERMISSION -> {
                if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                    // 通知权限已给
                }
            }
        }
    }

    companion object {
        private const val REQUEST_AUDIO_PERMISSION = 1001
        private const val REQUEST_NOTIFICATION_PERMISSION = 1002
    }
}
