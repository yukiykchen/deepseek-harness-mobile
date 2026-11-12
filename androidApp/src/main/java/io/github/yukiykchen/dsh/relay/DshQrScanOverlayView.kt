package io.github.yukiykchen.dsh.relay

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.util.AttributeSet
import android.view.View
import android.view.animation.LinearInterpolator
import kotlin.math.min

/**
 * BGA ScanBoxView 的视觉子集：遮罩、边框、四角、来回扫光线和一句提示。
 * 不参与解码，也不限制识别区域。
 */
internal class DshQrScanOverlayView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
) : View(context, attrs) {
    private val box = RectF()
    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
        color = MASK_COLOR
    }
    private val borderPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = FRAME_COLOR
    }
    private val cornerPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        color = FRAME_COLOR
        strokeCap = Paint.Cap.SQUARE
    }
    private val scanPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.FILL
    }
    private val tipPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        color = FRAME_COLOR
        textAlign = Paint.Align.CENTER
    }

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = SCAN_MS
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = LinearInterpolator()
        addUpdateListener { animation ->
            scanProgress = animation.animatedValue as Float
            invalidate()
        }
    }

    private var scanProgress = 0f
    private var insetTop = 0
    private var insetBottom = 0
    private var scanShader: Shader? = null

    init {
        importantForAccessibility = IMPORTANT_FOR_ACCESSIBILITY_NO
        isClickable = false
        isFocusable = false
    }

    fun setSystemInsets(top: Int, bottom: Int) {
        if (insetTop == top && insetBottom == bottom) return
        insetTop = top
        insetBottom = bottom
        layoutBox()
        invalidate()
    }

    fun start() {
        if (!animator.isStarted) animator.start()
    }

    fun stop() {
        animator.cancel()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        start()
    }

    override fun onDetachedFromWindow() {
        stop()
        super.onDetachedFromWindow()
    }

    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        layoutBox()
    }

    override fun onDraw(canvas: Canvas) {
        if (box.isEmpty) return
        drawMask(canvas)
        drawBorder(canvas)
        drawCorners(canvas)
        drawScanLine(canvas)
        drawTip(canvas)
    }

    private fun layoutBox() {
        if (width <= 0 || height <= 0) return
        val density = resources.displayMetrics.density
        val availW = width.toFloat()
        val availH = (height - insetTop - insetBottom).toFloat()
        val maxSize = 240f * density
        val tipSpace = 52f * density
        val size = min(maxSize, min(availW, availH - tipSpace) * 0.72f)
        val left = (availW - size) / 2f
        val top = insetTop + (availH - size - tipSpace) / 2f
        box.set(left, top, left + size, top + size)
        scanShader = null
        borderPaint.strokeWidth = 1f * density
        cornerPaint.strokeWidth = 3f * density
        tipPaint.textSize = 14f * resources.displayMetrics.scaledDensity
    }

    private fun drawMask(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        canvas.drawRect(0f, 0f, w, box.top, maskPaint)
        canvas.drawRect(0f, box.top, box.left, box.bottom, maskPaint)
        canvas.drawRect(box.right, box.top, w, box.bottom, maskPaint)
        canvas.drawRect(0f, box.bottom, w, h, maskPaint)
    }

    private fun drawBorder(canvas: Canvas) {
        canvas.drawRect(box, borderPaint)
    }

    private fun drawCorners(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val length = 20f * density
        val half = cornerPaint.strokeWidth / 2f
        val left = box.left
        val top = box.top
        val right = box.right
        val bottom = box.bottom
        canvas.drawLine(left - half, top, left - half + length, top, cornerPaint)
        canvas.drawLine(left, top - half, left, top - half + length, cornerPaint)
        canvas.drawLine(right + half, top, right + half - length, top, cornerPaint)
        canvas.drawLine(right, top - half, right, top - half + length, cornerPaint)
        canvas.drawLine(left - half, bottom, left - half + length, bottom, cornerPaint)
        canvas.drawLine(left, bottom + half, left, bottom + half - length, cornerPaint)
        canvas.drawLine(right + half, bottom, right + half - length, bottom, cornerPaint)
        canvas.drawLine(right, bottom + half, right, bottom + half - length, cornerPaint)
    }

    private fun drawScanLine(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val inset = 8f * density
        val lineHeight = 2f * density
        val travel = box.height() - inset * 2f - lineHeight
        if (travel <= 0f) return
        val left = box.left + inset
        val right = box.right - inset
        val top = box.top + inset + travel * scanProgress
        if (scanShader == null) {
            scanShader = LinearGradient(
                left,
                0f,
                right,
                0f,
                intArrayOf(0x0000E676, SCAN_LINE_COLOR, 0x0000E676),
                floatArrayOf(0f, 0.5f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        scanPaint.shader = scanShader
        canvas.drawRect(left, top, right, top + lineHeight, scanPaint)
        scanPaint.shader = null
    }

    private fun drawTip(canvas: Canvas) {
        val density = resources.displayMetrics.density
        val x = box.centerX()
        val y = box.bottom + 28f * density - tipPaint.ascent()
        canvas.drawText(TIP, x, y, tipPaint)
    }

    companion object {
        private const val MASK_COLOR = 0x88000000.toInt()
        private const val FRAME_COLOR = 0xFFFFFFFF.toInt()
        private const val SCAN_LINE_COLOR = 0xFF00E676.toInt()
        private const val SCAN_MS = 1400L
        private const val TIP = "扫描电脑二维码"
    }
}