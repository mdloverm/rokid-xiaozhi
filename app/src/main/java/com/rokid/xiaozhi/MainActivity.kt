package com.rokid.xiaozhi

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.view.WindowManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Log
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import com.rokid.xiaozhi.audio.AudioService
import com.rokid.xiaozhi.core.DeviceManager
import com.rokid.xiaozhi.network.XiaozhiWebSocketClient
import com.rokid.xiaozhi.util.WifiManager
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

class MainActivity : AppCompatActivity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val PERMISSION_REQUEST_CODE = 1001
    }

    private lateinit var webView: WebView

    private val audioService = AudioService()
    private val webSocket = XiaozhiWebSocketClient()
    private val appCacheDir: File by lazy { applicationContext.cacheDir }
    private var isCapturing = false
    private var deviceMac = ""
    private var deviceUuid = ""
    private var hasActivated = false
    private var isChatActive = false
    private var wifiReady = false
    private var permissionsGranted = false
    private var wifiSettingsOpened = false
    private val mainHandler = Handler(Looper.getMainLooper())
    private val wifiKeepAliveHandler = Handler(Looper.getMainLooper())

    private val wifiNetworkCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            val cm = applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val caps = cm.getNetworkCapabilities(network)
            if (caps != null && caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)) {
                Log.d(TAG, "WiFi 网络已可用")
                val ssid = WifiManager.getCurrentSsid(this@MainActivity)
                Log.d(TAG, "WiFi 已连接: $ssid")
                runOnUiThread {
                    setStatus("WiFi Ready")
                    wifiReady = true
                    unregisterWifiMonitor()
                    tryStartXiaozhi()
                }
            }
        }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webView)
        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
        }
        webView.webViewClient = object : WebViewClient() {
            override fun onPageFinished(view: WebView, url: String) {
                Log.d(TAG, "WebView 加载完成")
            }
        }
        webView.addJavascriptInterface(WebAppInterface(), "Android")
        webView.loadUrl("file:///android_asset/chat_ui.html")

        setStatus("Initializing...")
        initWifi()
        startWifiKeepAlive()
        setupCallbacks()
        requestPermissions()
    }

    inner class WebAppInterface {
    }

    private fun js(call: String) {
        mainHandler.post {
            try {
                webView.evaluateJavascript("javascript:$call", null)
            } catch (e: Exception) {
                Log.w(TAG, "JS 注入失败: $call", e)
            }
        }
    }

    private fun setStatus(text: String) {
        js("setStatus('$text')")
        Log.d(TAG, "状态: $text")
    }

    private fun addSystemMessage(text: String) {
        val safe = text.replace("'", "\\'").replace("\n", " ")
        js("addSystemMessage('$safe')")
    }

    private fun showVad(visible: Boolean) {
        js("setVadVisible($visible)")
    }

    private fun showAudioVis(visible: Boolean) {
        js("setAudioVisVisible($visible)")
    }

    override fun onResume() {
        super.onResume()
        if (wifiSettingsOpened && !wifiReady) {
            if (WifiManager.isWifiConnected(this)) {
                setStatus("WiFi Ready")
                wifiReady = true
                unregisterWifiMonitor()
                tryStartXiaozhi()
            }
        }
    }

    private fun initWifi() {
        Thread {
            if (WifiManager.isWifiConnected(this@MainActivity)) {
                wifiReady = true
                runOnUiThread { tryStartXiaozhi() }
                return@Thread
            }
            val enabled = WifiManager.enableWifi(this@MainActivity)
            if (!enabled) {
                wifiReady = true
                runOnUiThread { tryStartXiaozhi() }
                return@Thread
            }
            val autoConnected = WifiManager.waitForWifiConnection(this@MainActivity, 5000)
            if (autoConnected) {
                wifiReady = true
                runOnUiThread { tryStartXiaozhi() }
                return@Thread
            }
            runOnUiThread {
                setStatus("Connect WiFi...")
                wifiSettingsOpened = true
                registerWifiMonitor()
                WifiManager.openWifiSettings(this@MainActivity)
            }
        }.apply { name = "wifi-init" }.start()
    }

    private fun registerWifiMonitor() {
        WifiManager.registerNetworkCallback(this, wifiNetworkCallback)
    }

    private fun unregisterWifiMonitor() {
        try { WifiManager.unregisterNetworkCallback(this, wifiNetworkCallback) } catch (_: Exception) {}
    }

    private fun startWifiKeepAlive() {
        wifiKeepAliveHandler.postDelayed(object : Runnable {
            override fun run() {
                Thread {
                    try {
                        val conn = URL("https://api.tenclass.net").openConnection() as HttpURLConnection
                        conn.connectTimeout = 5000
                        conn.readTimeout = 5000
                        conn.connect()
                        conn.disconnect()
                    } catch (_: Exception) {}
                }.start()
                wifiKeepAliveHandler.postDelayed(this, 30000)
            }
        }, 10000)
    }

    private fun setupCallbacks() {
        audioService.setAudioDataCallback { opusData ->
            webSocket.sendAudio(opusData)
        }

        audioService.onSilenceDetected = {
            webSocket.sendListenStop()
            runOnUiThread {
                showVad(false)
                showAudioVis(false)
                setStatus("Listening...")
            }
        }

        audioService.onVoiceDetected = {
            webSocket.sendListenStart()
            runOnUiThread {
                showVad(true)
                showAudioVis(true)
                setStatus("Recording...")
            }
        }

        audioService.onSentencePlayStart = { sentenceText, durationMs ->
            Log.d(TAG, "逐句播放开始 dur=${durationMs}ms text=${sentenceText.take(20)}")
            mainHandler.post {
                val safe = sentenceText.replace("'", "\\'").replace("\n", " ")
                val speed = if (durationMs > 0) {
                    Math.max(18, Math.min(120, durationMs / sentenceText.length))
                } else 45
                js("startTyping('$safe', 'ai', $speed)")
            }
        }

        audioService.onSentencePlayDone = {
            mainHandler.post { js("finishTyping()") }
        }

        audioService.initialize(appCacheDir)

        webSocket.onConnected = {
            runOnUiThread { setStatus("Connected") }
        }

        webSocket.onSessionReady = {
            runOnUiThread {
                showChatView()
                setStatus("Listening...")
            }
            if (!isCapturing) {
                isCapturing = true
                audioService.startCapture()
            }
        }

        webSocket.onTextMessage = { type, text ->
            runOnUiThread {
                showChatView()
                when (type) {
                    "stt" -> {
                        setStatus("Recognizing...")
                        val safe = text.replace("'", "\\'").replace("\n", " ")
                        js("startTyping('$safe', 'user', 30)")
                    }
                    "llm" -> addSystemMessage("thinking...")
                    "tts_sentence_end" -> {
                        audioService.endSentence(text)
                    }
                    "tts_start" -> {
                        setStatus("Speaking...")
                        addSystemMessage("---")
                        audioService.onTtsStart()
                    }
                    "tts_stop" -> {
                        setStatus("Listening...")
                        addSystemMessage("complete")

                        audioService.onTtsStop()
                    }
                }
            }
        }

        webSocket.onAudioData = { opusData ->
            audioService.playOpus(opusData)
        }

        webSocket.onServerAudioParams = { sampleRate, frameDuration ->
            audioService.setServerAudioParams(sampleRate, frameDuration)
        }

        webSocket.onDisconnected = {
            runOnUiThread { setStatus("Disconnected") }
        }

        webSocket.onError = { error ->
            runOnUiThread {
                Toast.makeText(this, error, Toast.LENGTH_SHORT).show()
                addSystemMessage("error: $error")
            }
        }
    }

    private fun requestPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.RECORD_AUDIO,
            Manifest.permission.INTERNET,
            Manifest.permission.ACCESS_NETWORK_STATE
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions.add(Manifest.permission.BLUETOOTH_CONNECT)
        }
        val toRequest = permissions.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (toRequest.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, toRequest.toTypedArray(), PERMISSION_REQUEST_CODE)
        } else {
            permissionsGranted = true
            tryStartXiaozhi()
        }
    }

    private fun tryStartXiaozhi() {
        if (!permissionsGranted || !wifiReady) return
        startXiaozhi()
    }

    private fun startXiaozhi() {
        deviceMac = DeviceManager.getMac(this)
        deviceUuid = DeviceManager.getUuid(this)
        webSocket.setDeviceInfo(deviceMac, deviceUuid)
        addSystemMessage("device: $deviceMac")
        setStatus("Fetching config...")

        webSocket.fetchConfig(deviceMac, deviceUuid) { result ->
            runOnUiThread {
                if (result.activation != null && result.activation.code != null) {
                    showActivationCode(result.activation.code, result.activation.message)
                } else if (result.wsUrl != null) {
                    hasActivated = true
                    addSystemMessage("device activated, connecting...")
                    setStatus("Connecting...")
                    webSocket.connect(result.wsUrl, result.token)
                } else {
                    addSystemMessage("connection failed: no config")
                    setStatus("Failed")
                }
            }
        }
    }

    private fun showActivationCode(code: String, message: String?) {
        val safe = code.replace("'", "\\'").replace("\n", " ")
        js("""
            var c = document.getElementById('chatMessages');
            c.innerHTML = '';
            var el = document.createElement('div'); el.className = 'system';
            el.textContent = 'DEVICE ACTIVATION';
            el.style.fontSize = '12px'; el.style.marginTop = '40px';
            c.appendChild(el);
            el = document.createElement('div'); el.className = 'ai';
            el.textContent = '$safe';
            el.style.textAlign = 'center'; el.style.fontSize = '28px';
            el.style.borderLeft = 'none'; el.style.padding = '16px';
            c.appendChild(el);
            el = document.createElement('div'); el.className = 'system';
            el.textContent = 'open xiaozhi.me in browser';
            c.appendChild(el);
            el = document.createElement('div'); el.className = 'system';
            el.textContent = 'auto-connect after binding';
            el.style.opacity = '0.6';
            c.appendChild(el);
        """.trimIndent())
        setStatus("Waiting...")

        Thread {
            for (i in 1..60) {
                if (hasActivated) break
                Thread.sleep(3000)
                webSocket.fetchConfig(deviceMac, deviceUuid) { result ->
                    if (result.activation == null || result.activation.code == null) {
                        hasActivated = true
                        runOnUiThread {
                            showChatView()
                            addSystemMessage("activation success! connecting...")
                            setStatus("Connecting...")
                            if (result.wsUrl != null) webSocket.connect(result.wsUrl, result.token)
                            else webSocket.connect()
                        }
                    }
                }
            }
        }.start()
    }

    private fun showChatView() {
        if (isChatActive) return
        isChatActive = true
        js("document.getElementById('chatMessages').innerHTML = ''")
        addSystemMessage("session started")
    }

    override fun onRequestPermissionsResult(
        requestCode: Int, permissions: Array<out String>, grantResults: IntArray
    ) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == PERMISSION_REQUEST_CODE) {
            if (grantResults.all { it == PackageManager.PERMISSION_GRANTED }) {
                permissionsGranted = true
                tryStartXiaozhi()
            } else {
                Toast.makeText(this, "需要录音权限", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterWifiMonitor()
        wifiKeepAliveHandler.removeCallbacksAndMessages(null)
        hasActivated = true // 阻止销毁后的激活轮询回调（避免在已销毁Activity上执行）
        webView.destroy()
        webSocket.disconnect()
        audioService.release()
    }

    override fun onBackPressed() {
        webSocket.disconnect()
        audioService.release()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
            finishAndRemoveTask()
        } else {
            finish()
        }
        android.os.Process.killProcess(android.os.Process.myPid())
    }

    override fun onStop() {
        super.onStop()
        if (isFinishing) {
            webSocket.disconnect()
            audioService.release()
        }
    }
}
