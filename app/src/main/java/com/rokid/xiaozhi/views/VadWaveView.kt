package com.rokid.xiaozhi.views

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.util.AttributeSet
import android.view.View
import kotlin.random.Random

class VadWaveView @JvmOverloads constructor(
    context: Context, attrs: AttributeSet? = null, defStyleAttr: Int = 0
) : View(context, attrs, defStyleAttr) {

    private val paint = Paint().apply {
        color = Color.argb(153, 0, 255, 65)
        strokeWidth = 2f
        strokeCap = Paint.Cap.ROUND
    }
    private val barHeights = FloatArray(7) { 6f }
    private var animator: ValueAnimator? = null

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator = ValueAnimator.ofFloat(0f, 1f).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                for (i in barHeights.indices) {
                    barHeights[i] = Random.nextFloat() * height * 0.9f + 2f
                }
                invalidate()
            }
            start()
        }
    }

    override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        animator?.cancel()
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        val totalBars = barHeights.size
        val barWidth = 2f
        val gap = (width.toFloat() - barWidth * totalBars) / (totalBars + 1)
        for (i in barHeights.indices) {
            val x = gap + i * (barWidth + gap)
            val h = barHeights[i]
            canvas.drawRect(x, height - h, x + barWidth, height.toFloat(), paint)
        }
    }
}
