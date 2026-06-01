package com.wechatassistant.ai

import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParser
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException

class DeepSeekClient(private val apiKey: String) {

    private val client = OkHttpClient.Builder()
        .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
        .readTimeout(60, java.util.concurrent.TimeUnit.SECONDS)
        .build()

    private val gson = Gson()
    private val mainHandler = Handler(Looper.getMainLooper())

    interface Callback {
        fun onSuccess(actions: List<ActionItem>)
        fun onError(message: String)
    }

    data class ActionItem(
        val action: String,
        val packageName: String? = null,
        val x: Float? = null,
        val y: Float? = null,
        val text: String? = null,
        val duration: Int? = null,
        val seconds: Int? = null
    )

    private val systemPrompt = """
你是一个手机操作助手，可以通过 Android 无障碍服务控制手机。
用户会用语音指令告诉你做什么，你需要将其转为操作指令。

可用操作类型:
1. open_app - 打开应用 (参数: packageName)
2. tap - 点击 (参数: x, y 比例坐标 0~1)
3. swipe_up / swipe_down / swipe_left / swipe_right - 滑动
4. page_up / page_down - 翻页
5. next_page / prev_page - 点按翻页
6. back - 返回
7. home - 回到桌面
8. input_text - 输入文字 (参数: text)
9. wait - 等待 (参数: seconds)

常用包名:
- 微信: com.tencent.mm
- 微信读书: com.tencent.weread
- 抖音: com.ss.android.ugc.aweme

只返回 JSON: {"actions": [{"action": "...", ...}]}
""".trimIndent()

    fun understand(userInput: String, callback: Callback) {
        if (apiKey.isBlank()) {
            callback.onError("请先设置 DeepSeek API Key")
            return
        }

        val jsonBody = JSONObject().apply {
            put("model", "deepseek-chat")
            put("messages", JSONArray().apply {
                put(JSONObject().apply {
                    put("role", "system")
                    put("content", systemPrompt)
                })
                put(JSONObject().apply {
                    put("role", "user")
                    put("content", userInput)
                })
            })
            put("temperature", 0.1)
            put("max_tokens", 1000)
        }

        val request = Request.Builder()
            .url("https://api.deepseek.com/v1/chat/completions")
            .addHeader("Authorization", "Bearer $apiKey")
            .addHeader("Content-Type", "application/json")
            .post(jsonBody.toString().toRequestBody("application/json".toMediaType()))
            .build()

        client.newCall(request).enqueue(object : okhttp3.Callback {
            override fun onFailure(call: Call, e: IOException) {
                mainHandler.post { callback.onError("网络请求失败: ${e.message}") }
            }

            override fun onResponse(call: Call, response: Response) {
                val body = response.body?.string() ?: ""
                Log.d(TAG, "API 响应: $body")

                if (!response.isSuccessful) {
                    mainHandler.post { callback.onError("API 错误 ${response.code}: $body") }
                    return
                }

                try {
                    val json = JSONObject(body)
                    val content = json.getJSONArray("choices")
                        .getJSONObject(0)
                        .getJSONObject("message")
                        .getString("content")

                    val actions = parseActions(content)
                    mainHandler.post { callback.onSuccess(actions) }
                } catch (e: Exception) {
                    mainHandler.post { callback.onError("解析响应失败: ${e.message}") }
                }
            }
        })
    }

    private fun parseActions(responseText: String): List<ActionItem> {
        val actions = mutableListOf<ActionItem>()

        try {
            // 提取 JSON
            var json = responseText.trim()
            if (json.startsWith("```")) {
                val start = json.indexOf('{')
                val end = json.lastIndexOf('}') + 1
                json = json.substring(start, end)
            }

            val root = JsonParser.parseString(json).asJsonObject
            val actionsArray = root.getAsJsonArray("actions")

            for (element in actionsArray) {
                val obj = element.asJsonObject
                val action = obj.get("action").asString
                val item = ActionItem(
                    action = action,
                    packageName = obj.get("package")?.asString
                        ?: obj.get("packageName")?.asString,
                    x = obj.get("x")?.asFloat,
                    y = obj.get("y")?.asFloat,
                    text = obj.get("text")?.asString
                        ?: obj.get("content")?.asString,
                    duration = obj.get("duration")?.asInt,
                    seconds = obj.get("seconds")?.asInt ?: obj.get("wait")?.asInt
                )
                actions.add(item)
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析动作失败: ${e.message}")
        }

        return actions
    }

    companion object {
        private const val TAG = "DeepSeekClient"
    }
}
