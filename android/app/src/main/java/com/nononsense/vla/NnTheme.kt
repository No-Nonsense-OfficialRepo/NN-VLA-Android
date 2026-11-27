package com.nononsense.vla

import android.content.Context
import android.content.res.ColorStateList
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.Shader
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.RippleDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Build
import android.view.Gravity
import android.widget.Button

internal class NnTheme(private val context: Context) {
    val heading: Typeface = loadFont("nn/Cinzel.ttf", Typeface.SERIF)
    val body: Typeface = loadFont("nn/Inter.ttf", Typeface.SANS_SERIF)
    private val semibold = if (Build.VERSION.SDK_INT >= 28) Typeface.create(body, 600, false)
        else Typeface.create(body, Typeface.BOLD)

    private fun loadFont(path: String, fallback: Typeface): Typeface =
        runCatching { Typeface.createFromAsset(context.assets, path) }.getOrDefault(fallback)

    fun dp(value: Int) = (value * context.resources.displayMetrics.density + .5f).toInt()
    fun color(resource: Int) = context.getColor(resource)

    fun surface(fill: Int) = GradientDrawable().apply {
        setColor(color(fill))
        cornerRadius = dp(3).toFloat()
        setStroke(dp(1), color(R.color.nn_border))
    }

    fun button(label: String, icon: Int, accent: Int = R.color.nn_brass) =
        Button(context).apply {
            isAllCaps = false
            gravity = Gravity.CENTER_VERTICAL or Gravity.START
            typeface = semibold
            textSize = 14f
            setTextColor(ColorStateList(
                arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
                intArrayOf(color(R.color.nn_muted), color(R.color.nn_paper)),
            ))
            stateListAnimator = null
            backgroundTintList = null
            compoundDrawablePadding = dp(10)
            styleAction(this, label, icon, accent)
        }

    fun styleAction(button: Button, label: String, icon: Int, accent: Int) {
        if (button.text == label && button.tag == accent) return
        button.text = label
        button.tag = accent
        button.setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
        val states = StateListDrawable().apply {
            addState(intArrayOf(-android.R.attr.state_enabled), Chamfer(color(R.color.nn_muted), false))
            addState(intArrayOf(android.R.attr.state_focused), Chamfer(color(accent), true))
            addState(intArrayOf(), Chamfer(color(accent), false))
        }
        button.background = RippleDrawable(ColorStateList.valueOf(0x28ede9e1), states, Chamfer(Color.WHITE, false, mask = true))
        button.setPaddingRelative(dp(14), dp(10), dp(14), dp(10))
    }

    private inner class Chamfer(
        private val accent: Int,
        private val focused: Boolean,
        private val mask: Boolean = false,
    ) : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bodyPath = Path()
        private val trace = Path()

        override fun onBoundsChange(bounds: android.graphics.Rect) {
            val left = bounds.left + dp(1).toFloat()
            val right = bounds.right - dp(1).toFloat()
            val top = bounds.top + dp(1).toFloat()
            val bottom = bounds.bottom - dp(2).toFloat()
            val cut = dp(9).toFloat()
            bodyPath.reset()
            bodyPath.moveTo(left, top)
            bodyPath.lineTo(right, top)
            bodyPath.lineTo(right, bottom - cut)
            bodyPath.lineTo(right - cut, bottom)
            bodyPath.lineTo(left + cut, bottom)
            bodyPath.lineTo(left, bottom - cut)
            bodyPath.close()
            trace.reset()
            trace.moveTo(left, bottom - cut)
            trace.lineTo(left + cut, bottom)
            trace.lineTo(right - cut, bottom)
            trace.lineTo(right, bottom - cut)
        }

        override fun draw(canvas: Canvas) {
            if (bounds.isEmpty) return
            paint.style = Paint.Style.FILL
            paint.color = Color.WHITE
            paint.shader = if (mask) null else LinearGradient(
                0f, bounds.top.toFloat(), 0f, bounds.bottom.toFloat(),
                color(R.color.nn_content), color(R.color.nn_inset), Shader.TileMode.CLAMP,
            )
            canvas.drawPath(bodyPath, paint)
            if (mask) return
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = dp(if (focused) 2 else 1).toFloat()
            paint.shader = LinearGradient(
                bounds.left.toFloat(), 0f, bounds.right.toFloat(), 0f,
                intArrayOf(accent and 0x44ffffff, accent, accent and 0x44ffffff),
                null, Shader.TileMode.CLAMP,
            )
            canvas.drawPath(trace, paint)
            paint.shader = null
        }

        override fun setAlpha(alpha: Int) { paint.alpha = alpha }
        override fun setColorFilter(filter: ColorFilter?) { paint.colorFilter = filter }
        @Deprecated("Android Drawable API")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
