package com.rokid.xiaozhi.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.random.Random

class AudioVisView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint().apply {
        color = Color.argb(204, 0, 255, 65)
    }
    private val barCount = 12
    private val barHeights = FloatArray(barCount) { 2f }
    private var animator: ValueAnimator? = null
    private var running = false

    fun startAnim() {
        if (running) return
        running = true
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 600
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                for (i in barHeights.indices) {
                    barHeights[i] = Random.nextFloat() * (height - 4f) + 2f
                }
                invalidate()
            }
            start()
        }
    }

    fun stopAnim() {
        running = false
        animator?.cancel()
        barHeights.fill(2f)
        invalidate()
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        stopAnim()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val gap = 2f
        val totalGap = gap * (barCount + 1)
        val barWidth = (width.toFloat() - totalGap) / barCount
        for (i in 0 until barCount) {
            val x = gap + i * (barWidth + gap)
            val h = barHeights[i]
            paint.alpha = (50 + Random.nextFloat() * 150).toInt().coerceIn(50, 200)
            canvas.drawRect(x, height - h, x + barWidth, height.toFloat(), paint)
        }
    }
}
