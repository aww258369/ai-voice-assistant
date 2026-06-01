package com.wechatassistant.service

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Path
import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import com.wechatassistant.ai.DeepSeekClient
import kotlin.math.roundToInt

class AssistantService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var screenWidth = 0
    private var screenHeight = 0

    private val actionReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_EXECUTE) return
            val action = intent.getStringExtra("action") ?: return
            val item = DeepSeekClient.ActionItem(
                action = action,
                packageName = intent.getStringExtra("packageName"),
                x = intent.getFloatExtra("x", -1f).takeIf { it >= 0 },
                y = intent.getFloatExtra("y", -1f).takeIf { it >= 0 },
                text = intent.getStringExtra("text"),
                duration = intent.getIntExtra("duration", -1).takeIf { it >= 0 },
                seconds = intent.getIntExtra("seconds", -1).takeIf { it >= 0 }
            )
            executeAction(item) {}
        }
    }

    override fun onCreate() {
        super.onCreate()
        registerReceiver(actionReceiver, IntentFilter(ACTION_EXECUTE),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RECEIVER_EXPORTED else 0)
    }

    override fun onDestroy() {
        super.onDestroy()
        try { unregisterReceiver(actionReceiver) } catch (_: Exception) {}
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (screenWidth == 0 || screenHeight == 0) {
            val root = rootInActiveWindow ?: return
            val bounds = Rect()
            root.getBoundsInScreen(bounds)
            if (bounds.width() > 0 && bounds.height() > 0) {
                screenWidth = bounds.width()
                screenHeight = bounds.height()
            }
            root.recycle()
        }
    }

    override fun onInterrupt() {}
    override fun onServiceConnected() { super.onServiceConnected(); instance = this }

    private fun getScreenWidth(): Int {
        if (screenWidth == 0) {
            val root = rootInActiveWindow ?: return 0
            val bounds = Rect(); root.getBoundsInScreen(bounds); screenWidth = bounds.width(); root.recycle()
        }
        return screenWidth
    }

    private fun getScreenHeight(): Int {
        if (screenHeight == 0) {
            val root = rootInActiveWindow ?: return 0
            val bounds = Rect(); root.getBoundsInScreen(bounds); screenHeight = bounds.height(); root.recycle()
        }
        return screenHeight
    }

    fun executeAction(action: DeepSeekClient.ActionItem, callback: (Boolean) -> Unit) {
        when (action.action) {
            "open_app" -> openApp(action.packageName ?: "", callback)
            "tap" -> tap(action.x ?: 0.5f, action.y ?: 0.5f, callback)
            "swipe_up" -> swipeUp(action.duration ?: 300, callback)
            "swipe_down" -> swipeDown(action.duration ?: 300, callback)
            "swipe_left" -> swipeLeft(action.duration ?: 300, callback)
            "swipe_right" -> swipeRight(action.duration ?: 300, callback)
            "page_up", "page_down" -> swipeUp(500, callback)
            "next_page" -> tap(0.85f, 0.5f, callback)
            "prev_page" -> tap(0.15f, 0.5f, callback)
            "back" -> performGlobalAction(GLOBAL_ACTION_BACK).also { callback(it) }
            "home" -> performGlobalAction(GLOBAL_ACTION_HOME).also { callback(it) }
            "input_text", "text" -> inputText(action.text ?: "", callback)
            "wait" -> handler.postDelayed({ callback(true) }, (action.seconds ?: 1) * 1000L)
            else -> callback(false)
        }
    }

    private fun openApp(packageName: String, callback: (Boolean) -> Unit) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(packageName)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                handler.postDelayed({ callback(true) }, 2000)
            } else { callback(false) }
        } catch (e: Exception) { Log.e(TAG, "打开应用失败: ${e.message}"); callback(false) }
    }

    private fun tap(xRatio: Float, yRatio: Float, callback: (Boolean) -> Unit) {
        val x = (getScreenWidth().toFloat() * xRatio).roundToInt().toFloat()
        val y = (getScreenHeight().toFloat() * yRatio).roundToInt().toFloat()
        val path = Path().apply { moveTo(x, y); lineTo(x + 1, y + 1) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, 100)).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { callback(true) }
            override fun onCancelled(g: GestureDescription?) { callback(false) }
        }, null)
    }

    private fun swipeUp(d: Int, cb: (Boolean) -> Unit) {
        val w = getScreenWidth().toFloat(); val h = getScreenHeight().toFloat()
        performSwipe(w / 2, h * 0.75f, w / 2, h * 0.25f, d.toLong(), cb) }
    private fun swipeDown(d: Int, cb: (Boolean) -> Unit) {
        val w = getScreenWidth().toFloat(); val h = getScreenHeight().toFloat()
        performSwipe(w / 2, h * 0.25f, w / 2, h * 0.75f, d.toLong(), cb) }
    private fun swipeLeft(d: Int, cb: (Boolean) -> Unit) {
        val w = getScreenWidth().toFloat(); val h = getScreenHeight().toFloat()
        performSwipe(w * 0.75f, h / 2, w * 0.25f, h / 2, d.toLong(), cb) }
    private fun swipeRight(d: Int, cb: (Boolean) -> Unit) {
        val w = getScreenWidth().toFloat(); val h = getScreenHeight().toFloat()
        performSwipe(w * 0.25f, h / 2, w * 0.75f, h / 2, d.toLong(), cb) }

    private fun performSwipe(sx: Float, sy: Float, ex: Float, ey: Float, dur: Long, cb: (Boolean) -> Unit) {
        val path = Path().apply { moveTo(sx, sy); lineTo(ex, ey) }
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, dur)).build()
        dispatchGesture(gesture, object : GestureResultCallback() {
            override fun onCompleted(g: GestureDescription?) { cb(true) }
            override fun onCancelled(g: GestureDescription?) { cb(false) }
        }, null)
    }

    private fun inputText(text: String, callback: (Boolean) -> Unit) {
        // 复制到剪贴板
        val clipboard = getSystemService(CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(android.content.ClipData.newPlainText("label", text))

        // 找到当前焦点输入框 → 执行 PASTE 动作
        handler.postDelayed({
            val focused = findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
            if (focused != null) {
                val success = focused.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                focused.recycle()
                callback(success)
            } else {
                // 没找到焦点节点，试试全局焦点
                val root = rootInActiveWindow
                if (root != null) {
                    val result = root.performAction(AccessibilityNodeInfo.ACTION_PASTE)
                    root.recycle()
                    callback(result)
                } else {
                    callback(false)
                }
            }
        }, 300)
    }

    companion object {
        private const val TAG = "AssistantService"
        private const val ACTION_EXECUTE = "com.wechatassistant.EXECUTE_ACTION"
        var instance: AssistantService? = null
            private set
    }
}
