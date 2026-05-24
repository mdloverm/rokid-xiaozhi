package com.rokid.xiaozhi.util

import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.provider.Settings
import android.util.Log

object WifiManager {
    private const val TAG = "WifiManager"

    fun isWifiConnected(context: Context): Boolean {
        val connectivityManager = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val network = connectivityManager.activeNetwork ?: return false
        val capabilities = connectivityManager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }

    fun enableWifi(context: Context): Boolean {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        if (wifiManager.isWifiEnabled) {
            Log.d(TAG, "WiFi 已开启")
            return true
        }
        try {
            wifiManager.isWifiEnabled = true
            Log.d(TAG, "WiFi 已手动开启")
            return true
        } catch (e: Exception) {
            Log.e(TAG, "开启 WiFi 失败", e)
            return false
        }
    }

    fun getCurrentSsid(context: Context): String? {
        val wifiManager = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
        val connectionInfo = wifiManager.connectionInfo ?: return null
        return connectionInfo.ssid?.trim('"')
    }

    fun waitForWifiConnection(context: Context, timeoutMs: Long = 15000): Boolean {
        val startTime = System.currentTimeMillis()
        var attempts = 0
        while (System.currentTimeMillis() - startTime < timeoutMs) {
            if (isWifiConnected(context)) {
                Log.d(TAG, "WiFi 已连接")
                return true
            }
            attempts++
            if (attempts % 6 == 0) {
                Log.d(TAG, "等待 WiFi 连接中... (${System.currentTimeMillis() - startTime}ms)")
            }
            try {
                Thread.sleep(500)
            } catch (_: InterruptedException) {
                break
            }
        }
        Log.w(TAG, "等待 WiFi 连接超时 (${timeoutMs}ms)")
        return false
    }

    fun registerNetworkCallback(context: Context, callback: ConnectivityManager.NetworkCallback) {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            val request = android.net.NetworkRequest.Builder()
                .addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
                .build()
            cm.registerNetworkCallback(request, callback)
            Log.d(TAG, "已注册 WiFi 网络回调")
        } catch (e: Exception) {
            Log.e(TAG, "注册网络回调失败", e)
        }
    }

    fun unregisterNetworkCallback(context: Context, callback: ConnectivityManager.NetworkCallback) {
        try {
            val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
            cm.unregisterNetworkCallback(callback)
            Log.d(TAG, "已注销 WiFi 网络回调")
        } catch (e: Exception) {
            Log.e(TAG, "注销网络回调失败", e)
        }
    }

    fun openWifiSettings(context: Context) {
        Log.d(TAG, "打开 WiFi 设置界面")
        val intent = Intent(Settings.ACTION_WIFI_SETTINGS).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(intent)
    }
}
