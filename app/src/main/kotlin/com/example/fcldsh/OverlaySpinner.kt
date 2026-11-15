package com.example.fcldsh

import android.animation.ValueAnimator
import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.view.View
import android.view.animation.LinearInterpolator
import androidx.compose.ui.graphics.toArgb

/**
 * 悬浮窗里那个「转圈」的小圆环，表示 agent 正在思考。
 *
 * ## 为什么自己画，而不是用 `ProgressBar`
 *
 * `ProgressBar` 的不确定模式（`indeterminate`）用的是系统自带的
 * Material 动画素材，尺寸最小也有 30dp 左右，在 196dp 宽、字号 10sp 的
 * 悬浮卡片里会显得又大又突兀，颜色也不受控（跟着系统主题走，和 [Fcl]
 * 的主题色冲突）。这里只需要一个 12dp 的环，自己 drawArc 最省事：
 * 半径、线宽、颜色、转速全都能跟着 [Fcl.Theme] 走。
 *
 * ## 为什么在「可见性变化」里起停动画
 *
 * [ValueAnimator] 是**持续运行**的：只要没 cancel，它每帧都会回调并
 * invalidate。悬浮窗在没有思考时也会一直挂着，如果不随可见性停掉，
 * 就是在后台白烧 CPU/电量。所以：
 * - `VISIBLE` → 起动画；`GONE` / `INVISIBLE` → cancel；
 * - 视图脱离窗口（`onDetachedFromWindow`）也要 cancel，否则动画持有
 *   视图引用，浮窗关了还在转。
 */
internal class OverlaySpinner(context: Context) : View(context) {

    /** 线宽：1.6dp 在 12dp 的环上刚好，太粗会糊成一坨。 */
    private val strokePx = 1.6f * resources.displayMetrics.density

    private val arcBounds = RectF()

    /** 底圈：淡色的整圆，让「缺口在转」看得出来。 */
    private val trackPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
    }

    /** 前景：一段 270° 的弧。 */
    private val arcPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeWidth = strokePx
        strokeCap = Paint.Cap.ROUND
    }

    private var angle = 0f

    private var animator: ValueAnimator? = null

    override fun onDraw(canvas: Canvas) {
        val w = width.toFloat()
        val h = height.toFloat()
        if (w <= 0f || h <= 0f) return

        // 描边有一半在边界外，内缩半个线宽才不会被裁掉
        val inset = strokePx / 2f
        arcBounds.set(inset, inset, w - inset, h - inset)

        trackPaint.color = withAlpha(Fcl.Idle.toArgb(), 0x33)
        arcPaint.color = Fcl.Theme.toArgb()

        canvas.drawArc(arcBounds, 0f, 360f, false, trackPaint)
        // 留 90° 缺口 → 转起来能看出方向
        canvas.drawArc(arcBounds, angle, 270f, false, arcPaint)
    }

    /** 给一个 ARGB 换掉 alpha 通道。 */
    private fun withAlpha(argb: Int, alpha: Int): Int =
        (argb and 0x00FFFFFF) or ((alpha and 0xFF) shl 24)

    private fun startSpin() {
        if (animator != null) return
        animator = ValueAnimator.ofFloat(0f, 360f).apply {
            duration = 900L
            repeatCount = ValueAnimator.INFINITE
            // 匀速：Material 那种加减速在 12dp 的环上看起来会「一顿一顿」
            interpolator = LinearInterpolator()
            addUpdateListener {
                angle = (it.animatedValue as Float) % 360f
                invalidate()
            }
            start()
        }
    }

    private fun stopSpin() {
        animator?.cancel()
        animator = null
    }

    override fun onVisibilityChanged(changedView: View, visibility: Int) {
        super.onVisibilityChanged(changedView, visibility)
        if (visibility == VISIBLE) startSpin() else stopSpin()
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        if (visibility == VISIBLE) startSpin()
    }

    override fun onDetachedFromWindow() {
        stopSpin()
        super.onDetachedFromWindow()
    }
}
