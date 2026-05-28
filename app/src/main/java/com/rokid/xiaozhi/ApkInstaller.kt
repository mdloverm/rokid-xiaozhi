package com.rokid.xiaozhi

import android.content.Context
import android.content.Intent
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.core.content.FileProvider
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

class ApkInstaller(private val context: Context) {

    companion object {
        private const val TAG = "ApkInstaller"
    }

    interface Callback {
        fun onProgress(percent: Int, downloadedMb: String, totalMb: String)
        fun onMessage(message: String)
        fun onSuccess()
        fun onError(error: String)
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private var downloadThread: Thread? = null
    private var isCancelled = false
    private var callback: Callback? = null
    private var installed = false
    private val isRunning = AtomicBoolean(false)
    private var pollTask: Runnable? = null

    private var appUrl = ""
    private var appFilename = ""
    private var appPackageName = ""

    private val downloadedFile: File
        get() {
            val dir = context.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: context.filesDir
            return File(dir, appFilename)
        }

    private fun initParams(app: AppStoreItem) {
        appUrl = app.url
        appFilename = "${app.name}.apk"
        appPackageName = app.packageName
    }

    fun start(app: AppStoreItem, callback: Callback) {
        initParams(app)
        doStart(callback)
    }

    private fun doStart(callback: Callback) {
        if (!isRunning.compareAndSet(false, true)) {
            callback.onMessage("安装任务正在进行中，请稍候")
            return
        }
        this.callback = callback
        isCancelled = false
        installed = false
        Log.d(TAG, "start() called: url=$appUrl package=$appPackageName")
        startDownload()
    }

    fun cancel() {
        isCancelled = true
        downloadThread?.interrupt()
        stopPolling()
    }

    fun isInstalled(): Boolean = installed

    private fun startDownload() {
        Log.d(TAG, "startDownload()")
        callback?.onMessage("开始下载安装包...")
        downloadThread = Thread {
            val file = downloadedFile
            if (file.exists()) file.delete()

            try {
                val client = OkHttpClient.Builder()
                    .connectTimeout(30, TimeUnit.SECONDS)
                    .readTimeout(120, TimeUnit.SECONDS)
                    .build()

                val response = client.newCall(Request.Builder().url(appUrl).build()).execute()
                if (!response.isSuccessful) {
                    throw IOException("下载失败: HTTP ${response.code}")
                }

                val body = response.body ?: throw IOException("响应体为空")
                val totalBytes = body.contentLength()
                var downloadedBytes = 0L
                Log.d(TAG, "下载文件大小: $totalBytes bytes")

                val inputStream = body.byteStream()
                val outputStream = FileOutputStream(file)
                val buffer = ByteArray(8192)
                var bytesRead: Int

                try {
                    while (inputStream.read(buffer).also { bytesRead = it } != -1) {
                        if (isCancelled) {
                            file.delete()
                            isRunning.set(false)
                            return@Thread
                        }
                        outputStream.write(buffer, 0, bytesRead)
                        downloadedBytes += bytesRead
                        if (totalBytes > 0) {
                            val progress = ((downloadedBytes * 100) / totalBytes).toInt()
                            val downMb = String.format("%.1f", downloadedBytes / (1024.0 * 1024.0))
                            val totalMb = String.format("%.1f", totalBytes / (1024.0 * 1024.0))
                            mainHandler.post { callback?.onProgress(progress, downMb, totalMb) }
                        }
                    }
                    outputStream.flush()
                } finally {
                    inputStream.close()
                    outputStream.close()
                }
                Log.d(TAG, "下载完成, 文件: ${file.absolutePath}")

                mainHandler.post {
                    callback?.onMessage("下载完成")
                    installWithFileProvider(file)
                }

            } catch (e: Exception) {
                downloadedFile.delete()
                isRunning.set(false)
                mainHandler.post { callback?.onError("下载失败: ${e.message}") }
            }
        }.apply { name = "apk-download" }
        downloadThread?.start()
    }

    private fun installWithFileProvider(file: File) {
        Log.d(TAG, "installWithFileProvider()")
        try {
            file.setReadable(true, false)
            val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
            Log.d(TAG, "URI: $uri")
            val installIntent = Intent(Intent.ACTION_VIEW)
            installIntent.setDataAndType(uri, "application/vnd.android.package-archive")
            installIntent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
            installIntent.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            Log.d(TAG, "启动系统安装界面...")
            context.startActivity(installIntent)
            Log.d(TAG, "startActivity 完成")
            callback?.onMessage("请按眼镜确认键安装")
            startPolling()
        } catch (e: Exception) {
            Log.d(TAG, "FileProvider启动失败: ${e.message}")
            callback?.onError("安装界面启动失败: ${e.message}")
            isRunning.set(false)
        }
    }

    private fun startPolling() {
        stopPolling()
        pollCount = 0
        pollTask = Runnable { pollInstallResult() }
        mainHandler.postDelayed(pollTask!!, 3000)
    }

    private fun stopPolling() {
        pollTask?.let { mainHandler.removeCallbacks(it) }
        pollTask = null
    }

    private var pollCount = 0
    private val maxPolls = 40

    private fun pollInstallResult() {
        pollCount++
        Log.d(TAG, "pollInstallResult #$pollCount")
        if (checkPackageInstalled()) {
            Log.d(TAG, "包已安装成功!")
            installed = true
            downloadedFile.delete()
            isRunning.set(false)
            stopPolling()
            mainHandler.post { callback?.onSuccess() }
            return
        }
        if (pollCount >= maxPolls) {
            Log.d(TAG, "轮询超时，安装未完成")
            downloadedFile.delete()
            isRunning.set(false)
            stopPolling()
            mainHandler.post { callback?.onError("安装超时或取消") }
            return
        }
        pollTask = Runnable { pollInstallResult() }
        mainHandler.postDelayed(pollTask!!, 3000)
    }

    private fun checkPackageInstalled(): Boolean {
        return try {
            context.packageManager.getPackageInfo(appPackageName, 0)
            true
        } catch (_: Exception) {
            false
        }
    }

    fun deleteApkFile() {
        downloadedFile.delete()
    }
}
