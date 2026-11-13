package com.kairo.app

import android.animation.ValueAnimator
import android.app.Activity
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.animation.LinearInterpolator
import android.widget.LinearLayout
import android.widget.TextView

class OrbView(context: Context) : View(context) {
    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private var pulse = 0f

    private val animator = ValueAnimator.ofFloat(0f, 1f).apply {
        duration = 2400
        repeatCount = ValueAnimator.INFINITE
        repeatMode = ValueAnimator.REVERSE
        interpolator = LinearInterpolator()
        addUpdateListener {
            pulse = it.animatedValue as Float
            invalidate()
        }
    }

    override fun onAttachedToWindow() {
        super.onAttachedToWindow()
        animator.start()
    }

    override fun onDetachedFromWindow() {
        animator.cancel()
        super.onDetachedFromWindow()
    }

    override fun onDraw(canvas: Canvas) {
        val cx = width / 2f
        val cy = height / 2f
        val r = minOf(width, height) * 0.26f * (1f + 0.08f * pulse)

        paint.shader = RadialGradient(
            cx, cy, r * 2f,
            intArrayOf(
                Color.parseColor("#CC2979FF"),
                Color.parseColor("#442979FF"),
                Color.parseColor("#00000000")
            ),
            floatArrayOf(0f, 0.5f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r * 2f, paint)

        paint.shader = RadialGradient(
            cx, cy, r,
            intArrayOf(
                Color.parseColor("#FFBFD9FF"),
                Color.parseColor("#FF2979FF"),
                Color.parseColor("#FF0A1A4A")
            ),
            floatArrayOf(0f, 0.55f, 1f),
            Shader.TileMode.CLAMP
        )
        canvas.drawCircle(cx, cy, r, paint)
        paint.shader = null
    }
}

class MainActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setBackgroundColor(Color.parseColor("#050B1A"))
            fitsSystemWindows = true
            setPadding(48, 48, 48, 96)
        }

        val orb = OrbView(this)
        root.addView(
            orb,
            LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f)
        )

        val title = TextView(this).apply {
            text = "KAIRO"
            textSize = 36f
            letterSpacing = 0.3f
            setTextColor(Color.parseColor("#E8F0FF"))
            gravity = Gravity.CENTER
        }
        root.addView(title)

        val subtitle = TextView(this).apply {
            text = "Phase 1 \u2022 Installed and running"
            textSize = 14f
            setTextColor(Color.parseColor("#2979FF"))
            gravity = Gravity.CENTER
            setPadding(0, 16, 0, 0)
        }
        root.addView(subtitle)

        setContentView(root)
    }
}
