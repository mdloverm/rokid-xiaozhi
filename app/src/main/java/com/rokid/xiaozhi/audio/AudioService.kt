package com.rokid.xiaozhi.audio

import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaFormat
import android.media.MediaRecorder
import android.util.Log
import java.io.File
import java.nio.ByteBuffer

class AudioService {

    companion object {
        private const val TAG = "AudioService"
        const val SAMPLE_RATE = 16000
        const val CHANNELS = 1
        const val BIT_RATE = 32000
        const val FRAME_DURATION_MS = 60
        const val FRAME_SIZE = SAMPLE_RATE * FRAME_DURATION_MS / 1000
        const val FRAME_BYTES = FRAME_SIZE * 2

        private const val OPUS_MIME = MediaFormat.MIMETYPE_AUDIO_OPUS
        private const val VAD_THRESHOLD = 80
        private const val SILENCE_FRAMES_LIMIT = 33
    }

    private var encoder: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var isCapturing = false
    @Volatile private var captureThread: Thread? = null

    private var audioDataCallback: ((ByteArray) -> Unit)? = null
    var onSilenceDetected: (() -> Unit)? = null
    var onVoiceDetected: (() -> Unit)? = null

    private var isSpeaking = false
    private var silenceFrameCount = 0
    private var frameCount = 0

    private var opusDecoder: OpusDecoder? = null
    private var serverSampleRate = 16000

    fun setAudioDataCallback(callback: (ByteArray) -> Unit) {
        audioDataCallback = callback
    }

    fun initialize(cacheDir: File) {
        initEncoder()
        opusDecoder = OpusDecoder(cacheDir)
        opusDecoder?.onSentencePlayStart = { text, duration ->
            onSentencePlayStart?.invoke(text, duration)
        }
        opusDecoder?.onSentencePlayDone = {
            onSentencePlayDone?.invoke()
        }
        Log.d(TAG, "音频服务初始化成功")
    }

    fun setServerAudioParams(sampleRate: Int, frameDurationMs: Int) {
        Log.d(TAG, "服务器音频参数: ${sampleRate}Hz, ${frameDurationMs}ms")
        serverSampleRate = sampleRate
    }

    fun onTtsStart() {
        Log.d(TAG, "TTS 开始 sr=$serverSampleRate")
        opusDecoder?.start(serverSampleRate)
    }

    var onSentencePlayStart: ((text: String, durationMs: Int) -> Unit)? = null
    var onSentencePlayDone: (() -> Unit)? = null

    fun onTtsStop() {
        Log.d(TAG, "TTS 结束")
        opusDecoder?.stop()
    }

    fun endSentence(text: String) {
        opusDecoder?.endSentence(text)
    }

    private fun initEncoder() {
        try {
            val format = MediaFormat.createAudioFormat(OPUS_MIME, SAMPLE_RATE, CHANNELS).apply {
                setInteger(MediaFormat.KEY_BIT_RATE, BIT_RATE)
                setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, FRAME_BYTES)
            }
            encoder = MediaCodec.createEncoderByType(OPUS_MIME)
            encoder?.configure(format, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
            encoder?.start()
            Log.d(TAG, "Opus 编码器初始化成功")
        } catch (e: Exception) {
            Log.e(TAG, "Opus 编码器初始化失败", e)
        }
    }

    fun startCapture() {
        if (isCapturing) return
        isCapturing = true
        isSpeaking = false
        silenceFrameCount = 0

        captureThread = Thread({
            val bufferSize = AudioRecord.getMinBufferSize(
                SAMPLE_RATE, AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_16BIT
            )
            audioRecord = AudioRecord(
                MediaRecorder.AudioSource.MIC,
                SAMPLE_RATE,
                AudioFormat.CHANNEL_IN_MONO,
                AudioFormat.ENCODING_PCM_16BIT,
                bufferSize * 4
            )

            if (audioRecord?.state != AudioRecord.STATE_INITIALIZED) {
                Log.e(TAG, "AudioRecord 初始化失败")
                isCapturing = false
                return@Thread
            }

            try {
                audioRecord?.startRecording()
                Log.d(TAG, "麦克风开始采集")

                val pcmBuffer = ByteArray(FRAME_BYTES)
                val encBuf = ByteBuffer.allocateDirect(FRAME_BYTES)
                val encBufInfo = MediaCodec.BufferInfo()

                while (isCapturing) {
                    val read = audioRecord?.read(pcmBuffer, 0, pcmBuffer.size) ?: -1
                    if (read <= 0) continue

                    val energy = computeEnergy(pcmBuffer, read)
                    frameCount++
                    if (frameCount % 10 == 0) {
                        Log.d(TAG, "VAD energy=$energy threshold=$VAD_THRESHOLD")
                    }
                    if (energy > VAD_THRESHOLD) {
                        silenceFrameCount = 0
                        if (!isSpeaking) {
                            isSpeaking = true
                            onVoiceDetected?.invoke()
                        }
                    } else {
                        silenceFrameCount++
                        if (isSpeaking && silenceFrameCount >= SILENCE_FRAMES_LIMIT) {
                            isSpeaking = false
                            silenceFrameCount = 0
                            onSilenceDetected?.invoke()
                        }
                    }

                    val enc = encoder ?: continue
                    try {
                        val inputIndex = enc.dequeueInputBuffer(10000)
                        if (inputIndex >= 0) {
                            val inputBuf = enc.getInputBuffer(inputIndex) ?: continue
                            inputBuf.clear()
                            inputBuf.put(pcmBuffer, 0, read)
                            enc.queueInputBuffer(inputIndex, 0, read, System.nanoTime() / 1000, 0)
                        }

                        encBuf.clear()
                        var outputIndex = enc.dequeueOutputBuffer(encBufInfo, 10000)
                        while (outputIndex >= 0) {
                            val outputBuf = enc.getOutputBuffer(outputIndex) ?: break
                            encBuf.clear()
                            encBuf.put(outputBuf)
                            encBuf.flip()
                            val opusData = ByteArray(encBufInfo.size)
                            encBuf.get(opusData)
                            audioDataCallback?.invoke(opusData)
                            enc.releaseOutputBuffer(outputIndex, false)
                            outputIndex = enc.dequeueOutputBuffer(encBufInfo, 0)
                        }
                    } catch (e: Exception) {
                        Log.e(TAG, "编码异常", e)
                    }
                }
            } catch (e: Exception) {
                Log.e(TAG, "麦克风采集异常", e)
            } finally {
                try {
                    audioRecord?.stop()
                    audioRecord?.release()
                } catch (_: Exception) {}
                audioRecord = null
                isCapturing = false
                captureThread = null
                Log.d(TAG, "麦克风采集已停止")
            }
        }, "mic-capture")
        captureThread?.start()
    }

    private fun computeEnergy(buffer: ByteArray, bytesRead: Int): Long {
        var sum: Long = 0
        val samples = bytesRead / 2
        for (i in 0 until samples) {
            val sample = (buffer[i * 2].toInt() and 0xFF) or (buffer[i * 2 + 1].toInt() shl 8)
            sum += kotlin.math.abs(sample)
        }
        return if (samples > 0) sum / samples else 0
    }

    fun stopCapture() {
        isCapturing = false
        captureThread?.join(1000)
    }

    fun playOpus(opusData: ByteArray) {
        if (opusData.isEmpty()) return
        opusDecoder?.feed(opusData)
    }

    fun release() {
        stopCapture()
        try {
            encoder?.stop()
            encoder?.release()
        } catch (_: Exception) {}
        encoder = null
        try {
            opusDecoder?.release()
        } catch (_: Exception) {}
        opusDecoder = null
        Log.d(TAG, "音频服务已释放")
    }
}
