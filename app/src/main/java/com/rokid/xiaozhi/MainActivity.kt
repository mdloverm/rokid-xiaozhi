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
import com.rokid.xiaozhi.camera.CameraService
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
        private const val CAMERA_PERMISSION_REQUEST_CODE = 1002
    }

    private lateinit var webView: WebView

    private val audioService = AudioService()
    private val webSocket = XiaozhiWebSocketClient()
    private val cameraService = CameraService()
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
        setupCamera()
        requestPermissions()
    }

    inner class WebAppInterface {
    }

    private fun setupCamera() {
        cameraService.onPhotoTaken = { uri ->
            runOnUiThread {
                showPhotoPreview(uri)
                cameraService.closeCamera()
            }
        }

        cameraService.onError = { error ->
            runOnUiThread {
                addSystemMessage("相机错误: $error")
                setStatus("Listening...")
            }
        }

        }

    private fun handleVoiceCommand(text: String) {
        Log.d(TAG, "handleVoiceCommand: $text")
        if (text.contains("拍照") || text.contains("照相") || text.contains("拍一张")) {
            Log.d(TAG, "检测到拍照指令")
            requestCameraPermissionAndTakePhoto()
        }
    }

    private fun showPhotoPreview(uri: android.net.Uri) {
        Thread {
            try {
                val inputStream = contentResolver.openInputStream(uri)
                val bytes = inputStream?.readBytes() ?: return@Thread
                inputStream.close()
                val base64 = android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
                val dataUrl = "data:image/jpeg;base64,$base64"
                val safeUrl = dataUrl.replace("'", "\\'")

                mainHandler.post {
                    js("""
                        var oldOverlay = document.getElementById('photoPreviewOverlay');
                        if (oldOverlay) oldOverlay.remove();

                        var chatArea = document.getElementById('chatArea') || document.querySelector('.chat-area');
                        if (!chatArea) chatArea = document.body;
                        var rect = chatArea.getBoundingClientRect();

                        var overlay = document.createElement('div');
                        overlay.id = 'photoPreviewOverlay';
                        var css = 'position: fixed;' +
                            'top: ' + rect.top + 'px;' +
                            'left: ' + rect.left + 'px;' +
                            'width: ' + rect.width + 'px;' +
                            'height: ' + rect.height + 'px;' +
                            'background: rgba(0,0,0,0.95);' +
                            'display: flex; flex-direction: column;' +
                            'align-items: center; justify-content: center;' +
                            'z-index: 1000;' +
                            'border: 2px solid #00FF41;' +
                            'box-shadow: 0 0 20px rgba(0,255,65,0.2);';
                        overlay.style.cssText = css;

                        var img = document.createElement('img');
                        img.src = '$safeUrl';
                        img.style.cssText = 'max-width: 85%; max-height: 65%; object-fit: contain; border-radius: 4px;';
                        overlay.appendChild(img);

                        var hint = document.createElement('div');
                        hint.style.cssText = 'font-size: 12px; color: rgba(0,255,65,0.6); margin-top: 14px; letter-spacing: 2px;';
                        hint.textContent = '3秒后返回...';
                        overlay.appendChild(hint);

                        var countdown = document.createElement('div');
                        countdown.style.cssText = 'font-size: 36px; color: #00FF41; font-weight: bold; text-shadow: 0 0 20px rgba(0,255,65,0.8); margin-top: 6px;';
                        countdown.textContent = '3';
                        overlay.appendChild(countdown);

                        document.body.appendChild(overlay);

                        var count = 3;
                        var timer = setInterval(function() {
                            count--;
                            if (count > 0) {
                                countdown.textContent = count;
                            } else {
                                clearInterval(timer);
                            }
                        }, 1000);

                        setTimeout(function() {
                            var overlay = document.getElementById('photoPreviewOverlay');
                            if (overlay) overlay.remove();
                            addSystemMessage('拍照成功');
                            setStatus('Listening...');
                        }, 3000);
                    """)
                }
            } catch (e: Exception) {
                Log.e(TAG, "读取照片失败", e)
                mainHandler.post {
                    addSystemMessage("照片读取失败")
                    setStatus("Listening...")
                }
            }
        }.apply { name = "photo-preview" }.start()
    }

    private fun requestCameraPermissionAndTakePhoto() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA)
            == PackageManager.PERMISSION_GRANTED) {
            takePhoto()
        } else {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.CAMERA),
                CAMERA_PERMISSION_REQUEST_CODE
            )
        }
    }

    private fun takePhoto() {
        setStatus("Initializing camera...")
        cameraService.onInitialized = {
            Log.d(TAG, "相机初始化完成，开始拍照")
            setStatus("Taking photo...")
            cameraService.takePhoto(this@MainActivity)
        }
        cameraService.onError = { error ->
            Log.e(TAG, "拍照失败: $error")
            setStatus("Listening...")
            Toast.makeText(this@MainActivity, error, Toast.LENGTH_SHORT).show()
        }
        cameraService.initialize(this, this)
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
                        handleVoiceCommand(text)
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
        js("showActivation('$safe')")
        setStatus("Waiting...")

        Thread {
            for (i in 1..60) {
                if (hasActivated) break
                Thread.sleep(3000)
                webSocket.fetchConfig(deviceMac, deviceUuid) { result ->
                    if (result.activation == null || result.activation.code == null) {
                        hasActivated = true
                        runOnUiThread {
                            js("hideActivation()")
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
        js("hideActivation()")
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
        } else if (requestCode == CAMERA_PERMISSION_REQUEST_CODE) {
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                takePhoto()
            } else {
                Toast.makeText(this, "需要相机权限", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterWifiMonitor()
        wifiKeepAliveHandler.removeCallbacksAndMessages(null)
        hasActivated = true
        webView.destroy()
        webSocket.disconnect()
        audioService.release()
        cameraService.release()
    }

    override fun onBackPressed() {
        webSocket.disconnect()
        audioService.release()
        cameraService.release()
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
            cameraService.release()
        }
    }
}
