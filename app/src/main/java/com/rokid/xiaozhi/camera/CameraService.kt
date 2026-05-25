package com.rokid.xiaozhi.camera

import android.content.Context
import android.graphics.ImageFormat
import android.net.Uri
import android.util.Log
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import java.io.File
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

class CameraService {

    companion object {
        private const val TAG = "CameraService"
    }

    private var imageCapture: ImageCapture? = null
    private var cameraExecutor: ExecutorService = Executors.newSingleThreadExecutor()
    private var cameraProvider: ProcessCameraProvider? = null
    private var isInitialized = false
    private var savedLifecycleOwner: LifecycleOwner? = null

    var onPhotoTaken: ((Uri) -> Unit)? = null
    var onError: ((String) -> Unit)? = null
    var onInitialized: (() -> Unit)? = null

    

    fun initialize(context: Context, lifecycleOwner: LifecycleOwner, previewView: PreviewView? = null) {
        savedLifecycleOwner = lifecycleOwner
        val cameraProviderFuture = ProcessCameraProvider.getInstance(context)

        cameraProviderFuture.addListener({
            cameraProvider = cameraProviderFuture.get()
            bindCameraUseCases(lifecycleOwner, previewView)
        }, ContextCompat.getMainExecutor(context))
    }

    private fun bindCameraUseCases(lifecycleOwner: LifecycleOwner, previewView: PreviewView?) {
        val cameraProvider = cameraProvider ?: return

        val preview = Preview.Builder()
            .build()
            .also {
                if (previewView != null) {
                    it.setSurfaceProvider(previewView.surfaceProvider)
                }
            }

        imageCapture = ImageCapture.Builder()
            .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .build()

        val cameraSelector = CameraSelector.DEFAULT_BACK_CAMERA

        try {
            cameraProvider.unbindAll()
            cameraProvider.bindToLifecycle(
                lifecycleOwner,
                cameraSelector,
                preview,
                imageCapture
            )
            isInitialized = true
            onInitialized?.invoke()
            Log.d(TAG, "相机初始化完成")
        } catch (exc: Exception) {
            Log.e(TAG, "Use case binding failed", exc)
            onError?.invoke("相机初始化失败: ${exc.message}")
        }
    }

    

    fun takePhoto(context: Context) {
        val capture = imageCapture ?: run {
            onError?.invoke("相机未初始化")
            return
        }

        var saveDir = context.externalMediaDirs.firstOrNull()
        if (saveDir == null) {
            saveDir = context.getExternalFilesDir(android.os.Environment.DIRECTORY_PICTURES)
        }
        if (saveDir == null) {
            saveDir = context.filesDir
        }

        saveDir.mkdirs()

        val photoFile = File(
            saveDir,
            "${SimpleDateFormat("yyyy-MM-dd-HH-mm-ss-SSS", Locale.CHINA).format(System.currentTimeMillis())}.jpg"
        )

        val outputOptions = ImageCapture.OutputFileOptions.Builder(photoFile).build()

        capture.takePicture(
            outputOptions,
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) {
                    val savedUri = outputFileResults.savedUri ?: Uri.fromFile(photoFile)
                    Log.d(TAG, "照片保存成功: $savedUri")
                    onPhotoTaken?.invoke(savedUri)
                }

                override fun onError(exception: ImageCaptureException) {
                    Log.e(TAG, "拍照失败", exception)
                    onError?.invoke("拍照失败: ${exception.message}")
                }
            }
        )
    }

    fun closeCamera() {
        cameraProvider?.unbindAll()
        imageCapture = null
        isInitialized = false
        Log.d(TAG, "相机已关闭")
    }

    fun release() {
        closeCamera()
        cameraExecutor.shutdown()
        Log.d(TAG, "CameraService 已释放")
    }

    fun isActive(): Boolean = isInitialized
}
