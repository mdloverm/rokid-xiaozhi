package com.rokid.xiaozhi

import android.content.Context
import android.os.Handler
import android.os.Looper
import android.util.Log
import com.google.gson.Gson
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.BufferedReader
import java.io.InputStreamReader
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class AppStoreManager(private val context: Context) {

    companion object {
        private const val TAG = "AppStoreManager"
        private const val STORE_URL = "https://gitee.com/dlover1314/rokid-xiaozhi/raw/main/app_store.json"
        private const val ASSET_FILE = "app_store.json"
    }

    interface Callback {
        fun onLoaded(apps: List<AppStoreItem>)
        fun onError(error: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val client = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .build()

    @Volatile
    var cachedApps: List<AppStoreItem> = emptyList()
        private set

    fun load(callback: Callback) {
        val responded = AtomicBoolean(false)

        loadFromNetwork(callback, responded)

        mainHandler.postDelayed({
            if (!responded.get()) {
                loadFromAsset(callback, responded)
            }
        }, 5000)
    }

    private fun loadFromAsset(callback: Callback, responded: AtomicBoolean) {
        try {
            val inputStream = context.assets.open(ASSET_FILE)
            val reader = BufferedReader(InputStreamReader(inputStream))
            val text = reader.readText()
            reader.close()
            val config = Gson().fromJson(text, AppStoreConfig::class.java)
            if (config.apps.isNotEmpty() && responded.compareAndSet(false, true)) {
                cachedApps = config.apps
                Log.d(TAG, "使用内置资源: ${config.apps.size} 个应用")
                mainHandler.post { callback.onLoaded(config.apps) }
            }
        } catch (e: Exception) {
            Log.d(TAG, "加载内置资源失败: ${e.message}")
            if (responded.compareAndSet(false, true)) {
                mainHandler.post { callback.onError("加载应用商店失败") }
            }
        }
    }

    private fun loadFromNetwork(callback: Callback, responded: AtomicBoolean) {
        Thread {
            try {
                val response = client.newCall(Request.Builder().url(STORE_URL).build()).execute()
                if (!response.isSuccessful) {
                    Log.d(TAG, "网络下载失败: HTTP ${response.code}")
                    return@Thread
                }
                val body = response.body?.string() ?: throw Exception("响应体为空")
                val config = Gson().fromJson(body, AppStoreConfig::class.java)
                if (config.apps.isNotEmpty() && responded.compareAndSet(false, true)) {
                    cachedApps = config.apps
                    Log.d(TAG, "使用远程数据: ${config.apps.size} 个应用")
                    mainHandler.post { callback.onLoaded(config.apps) }
                }
            } catch (e: Exception) {
                Log.d(TAG, "网络加载失败: ${e.message}")
            }
        }.apply { name = "app-store-network" }.start()
    }

    fun findByName(name: String): AppStoreItem? {
        val lower = name.lowercase().trim()
        if (lower.isEmpty()) return null
        
        for (app in cachedApps) {
            val appNameLower = app.name.lowercase()
            val displayNameLower = app.displayName.lowercase()
            
            if (appNameLower == lower || 
                displayNameLower == lower ||
                displayNameLower.contains(lower) ||
                appNameLower.contains(lower)) {
                return app
            }
            
            for (char in lower) {
                if (char.isLetterOrDigit() && 
                    (displayNameLower.contains(char.toString()) || 
                     appNameLower.contains(char.toString()))) {
                    return app
                }
            }
        }
        return null
    }
}
