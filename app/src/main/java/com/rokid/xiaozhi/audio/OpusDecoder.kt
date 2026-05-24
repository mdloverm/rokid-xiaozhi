package com.rokid.xiaozhi.audio

import android.media.MediaPlayer
import android.util.Log
import java.io.File
import java.io.FileOutputStream
import java.util.concurrent.LinkedBlockingQueue
import java.util.zip.CRC32

class OpusDecoder(private val cacheDir: File) {

    companion object {
        private const val TAG = "OpusDecoder"
        private const val OGG_PAGE_SERIAL = 0x58494F5A.toInt()
    }

    private val sentenceFrames = mutableListOf<ByteArray>()
    private val pendingSentences = LinkedBlockingQueue<SentenceTask>()
    @Volatile private var isActive = false
    private var currentSampleRate = 16000

    private var currentPlayer: MediaPlayer? = null
    private var nextPlayer: MediaPlayer? = null
    private var nextTask: SentenceTask? = null
    private var nextReady = false
    private var pendingStartTask: SentenceTask? = null

    var onSentencePlayStart: ((text: String, durationMs: Int) -> Unit)? = null
    var onSentencePlayDone: (() -> Unit)? = null

    data class SentenceTask(
        val file: File,
        val text: String,
    )

    fun start(sampleRate: Int) {
        if (isActive) return
        isActive = true
        currentSampleRate = sampleRate
        deletePendingFiles()
        sentenceFrames.clear()
        pendingSentences.clear()
        nextTask = null
        nextReady = false
        pendingStartTask = null
        try { currentPlayer?.stop() } catch (_: Exception) {}
        try { currentPlayer?.release() } catch (_: Exception) {}
        try { nextPlayer?.release() } catch (_: Exception) {}
        currentPlayer = null
        nextPlayer = null
    }

    fun feed(opusData: ByteArray) {
        if (!isActive || opusData.isEmpty()) return
        sentenceFrames.add(opusData)
    }

    fun endSentence(text: String) {
        if (!isActive) return
        val frames = sentenceFrames.toList()
        sentenceFrames.clear()
        if (frames.isEmpty()) return

        val file = try {
            buildOggFile(frames, currentSampleRate)
        } catch (e: Exception) {
            Log.e(TAG, "OGG构建失败", e)
            return
        }

        pendingSentences.offer(SentenceTask(file, text))
        if (!isBusy()) {
            playNext()
        }
    }

    fun stop() {
        if (!isActive) return
        isActive = false
    }

    fun release() {
        isActive = false
        sentenceFrames.clear()
        deletePendingFiles()
        try { nextTask?.file?.delete() } catch (_: Exception) {}
        try { pendingStartTask?.file?.delete() } catch (_: Exception) {}
        nextTask = null
        nextReady = false
        pendingStartTask = null
        try { currentPlayer?.stop() } catch (_: Exception) {}
        try { currentPlayer?.release() } catch (_: Exception) {}
        try { nextPlayer?.stop() } catch (_: Exception) {}
        try { nextPlayer?.release() } catch (_: Exception) {}
        currentPlayer = null
        nextPlayer = null
    }

    private fun isBusy(): Boolean {
        if (pendingStartTask != null) return true
        return try {
            currentPlayer?.isPlaying == true
        } catch (_: Exception) {
            false
        }
    }

    private fun playNext() {
        val task = pendingSentences.poll() ?: return

        val ready = nextPlayer
        if (ready != null && nextReady) {
            val readyTask = nextTask!!
            nextPlayer = null
            nextTask = null
            nextReady = false

            currentPlayer?.release()
            currentPlayer = ready
            ready.start()

            Log.d(TAG, "播放一句(预加载) duration=${ready.duration}ms text=${readyTask.text.take(20)}")
            onSentencePlayStart?.invoke(readyTask.text, ready.duration)
            prePrepareNext()
            return
        }

        pendingStartTask = task

        val mp = MediaPlayer().apply {
            setDataSource(task.file.absolutePath)
            setOnPreparedListener {
                pendingStartTask = null
                start()
                val dur = duration
                Log.d(TAG, "播放一句 duration=${dur}ms text=${task.text.take(20)}")
                onSentencePlayStart?.invoke(task.text, dur)
                prePrepareNext()
            }
            setOnCompletionListener {
                task.file.delete()
                Log.d(TAG, "一句播放完成 text=${task.text.take(20)}")
                onSentencePlayDone?.invoke()
                playNext()
            }
            setOnErrorListener { _, what, extra ->
                task.file.delete()
                Log.e(TAG, "播放错误 what=$what extra=$extra")
                pendingStartTask = null
                onSentencePlayDone?.invoke()
                playNext()
                true
            }
            prepareAsync()
        }

        currentPlayer?.release()
        currentPlayer = mp
    }

    private fun prePrepareNext() {
        val task = pendingSentences.poll() ?: return

        nextPlayer?.release()
        nextTask = task
        nextReady = false
        nextPlayer = MediaPlayer().apply {
            setDataSource(task.file.absolutePath)
            setOnPreparedListener {
                nextReady = true
            }
            setOnCompletionListener {
                task.file.delete()
                Log.d(TAG, "一句播放完成 text=${task.text.take(20)}")
                onSentencePlayDone?.invoke()
                playNext()
            }
            setOnErrorListener { _, what, extra ->
                task.file.delete()
                Log.e(TAG, "播放错误(预加载) what=$what extra=$extra")
                nextTask = null
                nextPlayer = null
                onSentencePlayDone?.invoke()
                playNext()
                true
            }
            prepareAsync()
        }
    }

    private fun deletePendingFiles() {
        var task: SentenceTask?
        do {
            task = pendingSentences.poll()
            try { task?.file?.delete() } catch (_: Exception) {}
        } while (task != null)
    }

    private fun buildOggFile(frames: List<ByteArray>, sampleRate: Int): File {
        val file = File(cacheDir, "sentence_${System.nanoTime()}.opus")
        val stream = FileOutputStream(file)
        val pageSeq = intArrayOf(0)

        val headPage = buildPage(buildOpusHead(sampleRate), 0L, pageSeq)
        stream.write(headPage)

        val tagsPage = buildPage(buildOpusTags(), 0L, pageSeq)
        stream.write(tagsPage)

        val samplesPerFrame = sampleRate * 60L / 1000L
        var granule = 0L
        for (frame in frames) {
            granule += samplesPerFrame
            val page = buildPage(frame, granule, pageSeq)
            stream.write(page)
        }

        val eosPage = buildPage(ByteArray(0), granule, pageSeq)
        stream.write(eosPage)

        stream.close()
        return file
    }

    private fun buildOpusHead(sampleRate: Int): ByteArray {
        val head = ByteArray(19)
        val sig = "OpusHead".toByteArray(Charsets.UTF_8)
        System.arraycopy(sig, 0, head, 0, 8)
        head[8] = 1
        head[9] = 1
        head[10] = 0; head[11] = 0
        head[12] = (sampleRate and 0xFF).toByte()
        head[13] = ((sampleRate shr 8) and 0xFF).toByte()
        head[14] = ((sampleRate shr 16) and 0xFF).toByte()
        head[15] = ((sampleRate shr 24) and 0xFF).toByte()
        head[16] = 0; head[17] = 0; head[18] = 0
        return head
    }

    private fun buildOpusTags(): ByteArray {
        val vendor = "xiaozhi".toByteArray(Charsets.UTF_8)
        val tags = ByteArray(8 + 4 + vendor.size + 4)
        System.arraycopy("OpusTags".toByteArray(Charsets.UTF_8), 0, tags, 0, 8)
        tags[8] = (vendor.size and 0xFF).toByte()
        tags[9] = ((vendor.size shr 8) and 0xFF).toByte()
        tags[10] = ((vendor.size shr 16) and 0xFF).toByte()
        tags[11] = ((vendor.size shr 24) and 0xFF).toByte()
        System.arraycopy(vendor, 0, tags, 12, vendor.size)
        tags[12 + vendor.size] = 0; tags[12 + vendor.size + 1] = 0
        tags[12 + vendor.size + 2] = 0; tags[12 + vendor.size + 3] = 0
        return tags
    }

    private fun buildPage(payload: ByteArray, granule: Long, pageSeq: IntArray): ByteArray {
        val segmentCount: Int
        val segmentTable: ByteArray
        if (payload.size <= 255) {
            segmentCount = 1
            segmentTable = ByteArray(1) { payload.size.toByte() }
        } else {
            val full = payload.size / 255
            val rem = payload.size % 255
            segmentCount = if (rem == 0) full else full + 1
            segmentTable = ByteArray(segmentCount)
            for (i in 0 until segmentCount - 1) segmentTable[i] = 255.toByte()
            segmentTable[segmentCount - 1] = if (rem == 0 && segmentCount > 0 && full > 0) 0.toByte() else rem.toByte()
        }

        val totalSize = 27 + segmentCount + payload.size
        val page = ByteArray(totalSize)
        System.arraycopy("OggS".toByteArray(Charsets.UTF_8), 0, page, 0, 4)
        page[4] = 0

        val headerType = if (pageSeq[0] == 0) 2.toByte() else 0.toByte()
        page[5] = headerType

        page[6] = (granule and 0xFFL).toByte()
        page[7] = ((granule shr 8) and 0xFFL).toByte()
        page[8] = ((granule shr 16) and 0xFFL).toByte()
        page[9] = ((granule shr 24) and 0xFFL).toByte()
        page[10] = ((granule shr 32) and 0xFFL).toByte()
        page[11] = ((granule shr 40) and 0xFFL).toByte()
        page[12] = ((granule shr 48) and 0xFFL).toByte()
        page[13] = ((granule shr 56) and 0xFFL).toByte()

        page[14] = (OGG_PAGE_SERIAL and 0xFF).toByte()
        page[15] = ((OGG_PAGE_SERIAL shr 8) and 0xFF).toByte()
        page[16] = ((OGG_PAGE_SERIAL shr 16) and 0xFF).toByte()
        page[17] = ((OGG_PAGE_SERIAL shr 24) and 0xFF).toByte()

        val seq = pageSeq[0]++
        page[18] = (seq and 0xFF).toByte()
        page[19] = ((seq shr 8) and 0xFF).toByte()
        page[20] = ((seq shr 16) and 0xFF).toByte()
        page[21] = ((seq shr 24) and 0xFF).toByte()

        page[22] = 0; page[23] = 0; page[24] = 0; page[25] = 0
        page[26] = segmentCount.toByte()

        System.arraycopy(segmentTable, 0, page, 27, segmentCount)
        System.arraycopy(payload, 0, page, 27 + segmentCount, payload.size)

        val crc = CRC32()
        crc.update(page, 0, totalSize)
        val crcVal = crc.value.toInt()
        page[22] = (crcVal and 0xFF).toByte()
        page[23] = ((crcVal shr 8) and 0xFF).toByte()
        page[24] = ((crcVal shr 16) and 0xFF).toByte()
        page[25] = ((crcVal shr 24) and 0xFF).toByte()

        return page
    }
}
