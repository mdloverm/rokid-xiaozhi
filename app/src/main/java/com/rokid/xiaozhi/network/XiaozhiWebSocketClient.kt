package com.rokid.xiaozhi.network

import android.os.Handler
import android.os.Looper
import android.util.Log
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import okio.ByteString
import org.json.JSONArray
import org.json.JSONObject
import java.util.concurrent.TimeUnit

class XiaozhiWebSocketClient {

    companion object {
        private const val TAG = "XiaozhiWS"
        private const val FALLBACK_URL = "wss://api.tenclass.net/xiaozhi/v1/"
        private const val OTA_URL = "https://api.tenclass.net/xiaozhi/ota/"
        private const val PING_INTERVAL = 30L
    }

    private var webSocket: WebSocket? = null
    private var currentSessionId = ""
    private var wsUrl = FALLBACK_URL
    private var isConnected = false
    private var sessionReadySent = false
    private var pendingToken: String? = null
    private var isConnecting = false
    private var deviceId = "xiaozhi-glasses"
    private var clientId = "xiaozhi-glasses"
    private val lock = Any()

    private val client = OkHttpClient.Builder()
        .pingInterval(PING_INTERVAL, TimeUnit.SECONDS)
        .build()

    var onConnected: (() -> Unit)? = null
    var onDisconnected: (() -> Unit)? = null
    var onSessionReady: ((String) -> Unit)? = null
    var onTextMessage: ((String, String) -> Unit)? = null
    var onAudioData: ((ByteArray) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onServerAudioParams: ((sampleRate: Int, frameDuration: Int) -> Unit)? = null

    var isListening = false

    fun setDeviceInfo(mac: String, uuid: String) {
        deviceId = mac
        clientId = uuid
    }

    data class ActivationInfo(
        val code: String?,
        val message: String?,
        val challenge: String?,
        val timeoutMs: Int = 30000
    )

    data class MqttConfig(
        val endpoint: String?,
        val clientId: String?,
        val username: String?,
        val password: String?,
        val publishTopic: String?,
        val subscribeTopic: String?
    )

    data class OtaResult(
        val wsUrl: String?,
        val token: String?,
        val activation: ActivationInfo? = null,
        val mqtt: MqttConfig? = null
    )

    fun fetchConfig(mac: String, uuid: String, callback: (OtaResult) -> Unit) {
        Thread {
            var lastError: Exception? = null
            for (attempt in 1..3) {
                try {
                    if (attempt > 1) {
                        Log.d(TAG, "OTA 重试第 $attempt 次...")
                        Thread.sleep(attempt * 1000L)
                    }

                    val body = JSONObject().apply {
                        put("version", 2)
                        put("flash_size", 16777216)
                        put("psram_size", 0)
                        put("minimum_free_heap_size", 262144)
                        put("mac_address", mac)
                        put("uuid", uuid)
                        put("chip_model_name", "24129PN74C")
                        put("application", JSONObject().apply {
                            put("name", "xiaozhi-glasses")
                            put("version", "1.0.0")
                            put("compile_time", "2025-01-01T00:00:00Z")
                        })
                        put("board", JSONObject().apply {
                            put("type", "xiaozhi-glasses")
                        })
                    }.toString()

                    val userAgent = "xiaozhi-glasses/1.0.0"

                    val request = Request.Builder()
                        .url(OTA_URL)
                        .header("Content-Type", "application/json")
                        .header("Device-Id", mac)
                        .header("Client-Id", uuid)
                        .header("User-Agent", userAgent)
                        .header("Activation-Version", "1")
                        .post(body.toRequestBody("application/json".toMediaType()))
                        .build()

                    val response = client.newCall(request).execute()
                    val bodyStr = response.body?.string() ?: "{}"
                    Log.d(TAG, "OTA config fetched successfully")

                    val json = JSONObject(bodyStr)

                    var activation: ActivationInfo? = null
                    if (json.has("activation")) {
                        val act = json.getJSONObject("activation")
                        activation = ActivationInfo(
                            code = act.optString("code", null),
                            message = act.optString("message", null),
                            challenge = act.optString("challenge", null),
                            timeoutMs = act.optInt("timeout_ms", 30000)
                        )
                    }

                    var wsUrl: String? = null
                    var wsToken: String? = null
                    if (json.has("websocket")) {
                        val ws = json.getJSONObject("websocket")
                        wsUrl = ws.optString("url", null)
                        wsToken = ws.optString("token", null)
                    }

                    var mqtt: MqttConfig? = null
                    if (json.has("mqtt")) {
                        val m = json.getJSONObject("mqtt")
                        mqtt = MqttConfig(
                            endpoint = m.optString("endpoint", null),
                            clientId = m.optString("client_id", null),
                            username = m.optString("username", null),
                            password = m.optString("password", null),
                            publishTopic = m.optString("publish_topic", null),
                            subscribeTopic = m.optString("subscribe_topic", null)
                        )
                    }

                    callback(OtaResult(
                        wsUrl = wsUrl,
                        token = wsToken,
                        activation = activation,
                        mqtt = mqtt
                    ))
                    return@Thread
                } catch (e: Exception) {
                    lastError = e
                    Log.w(TAG, "OTA 请求失败 (第 $attempt 次)", e)
                }
            }
            Log.e(TAG, "OTA 请求全部重试失败", lastError)
            callback(OtaResult(null, null))
        }.start()
    }

    fun connect(url: String? = null, token: String? = null) {
        synchronized(lock) {
            if (webSocket != null || isConnecting) return
            isConnecting = true
            reconnectPending = false
        }

        val finalUrl = url ?: FALLBACK_URL
        if (token.isNullOrEmpty()) {
            Log.w(TAG, "无有效 token，不连接 WebSocket")
            synchronized(lock) { isConnecting = false }
            return
        }

        wsUrl = finalUrl
        pendingToken = token
        Log.d(TAG, "WebSocket 连接: $finalUrl")

        val request = Request.Builder()
            .url(finalUrl)
            .header("Protocol-Version", "1")
            .header("Device-Id", deviceId)
            .header("Client-Id", clientId)
            .apply {
                if (token.isNotEmpty()) {
                    header("Authorization", "Bearer $token")
                }
            }
            .build()

        synchronized(lock) {
            webSocket = client.newWebSocket(request, object : WebSocketListener() {
                override fun onOpen(ws: WebSocket, response: Response) {
                    Log.d(TAG, "WebSocket 已打开")
                    synchronized(lock) { isConnecting = false }
                    reconnectAttempts = 0
                    sendHello()
                }

                override fun onMessage(ws: WebSocket, text: String) {
                    handleTextMessage(text)
                }

                override fun onMessage(ws: WebSocket, bytes: ByteString) {
                    Log.d(TAG, "收到二进制音频: ${bytes.size} bytes")
                    onAudioData?.invoke(bytes.toByteArray())
                }

                override fun onClosing(ws: WebSocket, code: Int, reason: String) {
                    ws.close(1000, null)
                }

                override fun onClosed(ws: WebSocket, code: Int, reason: String) {
                    Log.d(TAG, "WebSocket 已关闭: $code $reason")
                    synchronized(lock) {
                        isConnected = false
                        isConnecting = false
                        if (webSocket == ws) webSocket = null
                    }
                    onDisconnected?.invoke()
                    scheduleReconnect()
                }

                override fun onFailure(ws: WebSocket, t: Throwable, response: Response?) {
                    Log.e(TAG, "WebSocket 失败", t)
                    synchronized(lock) {
                        isConnected = false
                        isConnecting = false
                        if (webSocket == ws) webSocket = null
                    }
                    onError?.invoke(t.message ?: "连接失败")
                    scheduleReconnect()
                }
            })
        }
    }

    private fun sendHello() {
        val hello = JSONObject().apply {
            put("type", "hello")
            put("version", 1)
            put("transport", "websocket")
            put("features", JSONObject().apply {
                put("mcp", true)
            })
            put("audio_params", JSONObject().apply {
                put("format", "opus")
                put("sample_rate", 16000)
                put("channels", 1)
                put("frame_duration", 60)
            })
        }.toString()
        webSocket?.send(hello)
        Log.d(TAG, "已发送 hello")
    }

    private fun handleTextMessage(text: String) {
        Log.d(TAG, "Received message")
        try {
            val json = JSONObject(text)
            when (json.optString("type")) {
                "hello" -> {
                    currentSessionId = json.optString("session_id")
                    Log.d(TAG, "收到服务器 hello, session_id: $currentSessionId")

                    val audioParams = json.optJSONObject("audio_params")
                    if (audioParams != null) {
                        val sr = audioParams.optInt("sample_rate", 16000)
                        val fd = audioParams.optInt("frame_duration", 60)
                        onServerAudioParams?.invoke(sr, fd)
                    }

                    isConnected = true
                    onConnected?.invoke()
                }
                "start" -> {
                    Log.d(TAG, "收到 start，开始说话")
                    if (!sessionReadySent) {
                        sessionReadySent = true
                        onSessionReady?.invoke(currentSessionId)
                    }
                }
                "stt" -> {
                    val t = json.optString("text")
                    if (t.isNotEmpty()) {
                        onTextMessage?.invoke("stt", t)
                    }
                }
                "llm" -> {
                    val t = json.optString("text")
                    if (t.isNotEmpty()) {
                        onTextMessage?.invoke("llm", t)
                    }
                }
                "tts" -> {
                    val state = json.optString("state")
                    val t = json.optString("text")
                    when (state) {
                        "start" -> onTextMessage?.invoke("tts_start", "")
                        "stop" -> onTextMessage?.invoke("tts_stop", "")
                        "sentence_end" -> {
                            onTextMessage?.invoke("tts_sentence_end", t)
                        }
                    }
                }
                "mcp" -> {
                    handleMcpMessage(json)
                }
                "goodbye" -> {
                    Log.d(TAG, "收到 goodbye")
                    isConnected = false
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "解析消息失败", e)
        }
    }

    private fun handleMcpMessage(json: JSONObject) {
        try {
            val payloadStr = json.optString("payload")
            val payload = JSONObject(payloadStr)
            val id = payload.optInt("id")
            val method = payload.optString("method")

            when (method) {
                "initialize" -> {
                    val response = JSONObject().apply {
                        put("jsonrpc", "2.0")
                        put("id", id)
                        put("result", JSONObject().apply {
                            put("protocolVersion", "2024-11-05")
                            put("capabilities", JSONObject())
                        })
                    }
                    sendMcpReply(response.toString())
                    Log.d(TAG, "已回复 MCP 初始化")
                }
                "tools/list" -> {
                    val response = JSONObject().apply {
                        put("jsonrpc", "2.0")
                        put("id", id)
                        put("result", JSONObject().apply {
                            put("tools", JSONArray())
                        })
                    }
                    sendMcpReply(response.toString())
                    Log.d(TAG, "已回复 tools/list")
                    isConnected = true
                    if (!sessionReadySent) {
                        sessionReadySent = true
                        onSessionReady?.invoke(currentSessionId)
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "处理 MCP 消息失败", e)
        }
    }

    private fun sendMcpReply(payload: String) {
        try {
            val payloadObj = JSONObject(payload)
            val mcpReply = JSONObject().apply {
                put("session_id", currentSessionId)
                put("type", "mcp")
                put("payload", payloadObj)
            }.toString()
            webSocket?.send(mcpReply)
        } catch (e: Exception) {
            Log.e(TAG, "发送 MCP 回复失败", e)
        }
    }

    fun sendListenStart() {
        isListening = true
        val listen = JSONObject().apply {
            put("type", "listen")
            put("state", "start")
            put("session_id", currentSessionId)
        }.toString()
        webSocket?.send(listen)
        Log.d(TAG, "已发送 listen start")
    }

    fun sendListenStop() {
        isListening = false
        val listen = JSONObject().apply {
            put("type", "listen")
            put("state", "stop")
            put("session_id", currentSessionId)
        }.toString()
        webSocket?.send(listen)
        Log.d(TAG, "已发送 listen stop")
    }

    fun sendAudio(opusData: ByteArray) {
        if (isConnected && isListening) {
            webSocket?.send(ByteString.of(*opusData))
        }
    }

    private val reconnectHandler = Handler(Looper.getMainLooper())
    private var reconnectPending = false
    private var reconnectAttempts = 0

    private fun scheduleReconnect() {
        synchronized(lock) {
            if (reconnectPending) return
            reconnectPending = true
        }
        reconnectAttempts++
        val delay = minOf(3000L * (1 shl (reconnectAttempts.coerceAtMost(4))), 30000L)
        Log.d(TAG, "计划在 ${delay}ms 后重连 (第${reconnectAttempts}次)")
        reconnectHandler.postDelayed({
            synchronized(lock) {
                reconnectPending = false
                if (isConnected || isConnecting) return@postDelayed
            }
            Log.d(TAG, "尝试重连...")
            connect(wsUrl, pendingToken)
        }, delay)
    }

    fun disconnect() {
        synchronized(lock) {
            reconnectHandler.removeCallbacksAndMessages(null)
            reconnectPending = false
            reconnectAttempts = 0
            sessionReadySent = false
            webSocket?.close(1000, "客户端断开")
            webSocket = null
            isConnected = false
            isConnecting = false
        }
    }

    fun isConnected(): Boolean = isConnected
}
