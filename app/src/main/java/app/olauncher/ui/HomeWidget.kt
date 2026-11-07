package app.olauncher.ui

import android.animation.ValueAnimator
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.PixelFormat
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.drawable.Drawable
import android.text.format.DateFormat
import android.util.TypedValue
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import android.view.ViewGroup
import android.widget.SeekBar
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import app.olauncher.R
import app.olauncher.data.Prefs
import app.olauncher.databinding.LayoutHomeWidgetBinding
import app.olauncher.helper.dpToPx
import app.olauncher.helper.isEinkDisplay
import app.olauncher.helper.isSystemAnimationsDisabled
import kotlin.math.min
import kotlin.math.abs
import kotlin.math.roundToInt

class HomeWidget(
    private val binding: LayoutHomeWidgetBinding,
    private val prefs: Prefs,
) {
    private val circle = binding.widgetCircle
    private var display = 0
    private var dragging = false
    private val touchSlop = ViewConfiguration.get(circle.context).scaledTouchSlop
    var onResizeVisibilityChanged: (Boolean) -> Unit = {}
    private var downX = 0f
    private var downY = 0f
    private var startX = 0f
    private var startY = 0f
    private var safeLeft = 0
    private var safeTop = 0
    private var safeRight = 0
    private var safeBottom = 0
    private var charging = false
    private var active = false
    private var pulse: ValueAnimator? = null
    private val detector = GestureDetector(circle.context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true

        override fun onSingleTapUp(event: MotionEvent): Boolean {
            circle.performClick()
            return true
        }

        override fun onLongPress(event: MotionEvent) {
            circle.performLongClick()
        }

        override fun onScroll(first: MotionEvent?, current: MotionEvent, distanceX: Float, distanceY: Float): Boolean {
            if (first == null || dragging) return false
            val dy = current.y - first.y
            val dx = current.x - first.x
            if (dy < -touchSlop * 2 && abs(dy) > abs(dx)) {
                showResize()
                return true
            }
            return false
        }
    })

    init {
        binding.widgetSizeSlider.progress = prefs.homeWidgetCircleSize - 56
        binding.widgetSizeSlider.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                prefs.homeWidgetCircleSize = progress + 56
                applySize()
            }

            override fun onStartTrackingTouch(seekBar: SeekBar) = Unit
            override fun onStopTrackingTouch(seekBar: SeekBar) = Unit
        })
        binding.widgetResizeOutside.setOnClickListener { hideResize() }
        binding.widgetResizeDone.setOnClickListener { hideResize() }
        ViewCompat.addAccessibilityAction(circle, circle.context.getString(R.string.widget_circle_size)) { _, _ ->
            showResize()
            true
        }
        binding.widgetChargingGlow.background = ChargingGlowDrawable()
        circle.addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
            override fun onViewAttachedToWindow(view: View) = updateChargingGlow()
            override fun onViewDetachedFromWindow(view: View) = stopPulse()
        })
        circle.setOnClickListener {
            display = (display + 1) % 3
            updateDisplay()
        }
        ViewCompat.replaceAccessibilityAction(
            circle,
            AccessibilityNodeInfoCompat.AccessibilityActionCompat.ACTION_CLICK,
            circle.context.getString(R.string.widget_circle_hint),
            null,
        )
        circle.setOnLongClickListener {
            dragging = true
            circle.isPressed = true
            circle.parent.requestDisallowInterceptTouchEvent(true)
            true
        }
        circle.setOnTouchListener { _, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) {
                downX = event.rawX
                downY = event.rawY
                startX = circle.x
                startY = circle.y
                dragging = false
            }
            detector.onTouchEvent(event)
            when (event.actionMasked) {
                MotionEvent.ACTION_MOVE -> if (dragging) {
                    place(startX + event.rawX - downX, startY + event.rawY - downY)
                }
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> {
                    if (dragging) {
                        if (event.actionMasked == MotionEvent.ACTION_UP) {
                            place(startX + event.rawX - downX, startY + event.rawY - downY)
                            savePosition()
                        } else {
                            restorePosition()
                        }
                    }
                    dragging = false
                    circle.isPressed = false
                    circle.parent.requestDisallowInterceptTouchEvent(false)
                }
            }
            true
        }
        binding.root.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> restorePosition() }
        circle.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> restorePosition() }
        applySize()
        updateDisplay()
        updateTimeFormat()
    }

    fun setActive(value: Boolean) {
        active = value
        if (active) updateTimeFormat()
        else hideResize()
        updateChargingGlow()
    }

    fun setCharging(value: Boolean) {
        charging = value
        updateChargingGlow()
    }

    private fun updateTimeFormat() {
        binding.widgetAmPm.isVisible = !DateFormat.is24HourFormat(circle.context)
    }

    private fun updateChargingGlow() {
        val glow = binding.widgetChargingGlow
        glow.isVisible = charging && active && circle.isVisible && circle.isAttachedToWindow
        if (!glow.isVisible || circle.context.isSystemAnimationsDisabled() || circle.context.isEinkDisplay()) {
            stopPulse()
            return
        }
        if (pulse != null) return
        pulse = ValueAnimator.ofFloat(0.3f, 1f).apply {
            duration = 1400
            repeatCount = ValueAnimator.INFINITE
            repeatMode = ValueAnimator.REVERSE
            addUpdateListener { glow.alpha = it.animatedValue as Float }
            start()
        }
    }

    private fun stopPulse() {
        pulse?.cancel()
        pulse = null
        binding.widgetChargingGlow.alpha = 1f
    }

    fun applyInsets(left: Int, top: Int, right: Int, bottom: Int) {
        safeLeft = left
        safeTop = top
        safeRight = right
        safeBottom = bottom
        val params = binding.widgetResizePanel.layoutParams as ViewGroup.MarginLayoutParams
        params.setMargins(left + 12.dpToPx(), top + 12.dpToPx(), right + 12.dpToPx(), bottom + 12.dpToPx())
        binding.widgetResizePanel.layoutParams = params
        restorePosition()
    }

    private fun showResize() {
        if (binding.widgetResizePanel.isVisible) return
        binding.widgetResizeOutside.isVisible = true
        binding.widgetResizePanel.isVisible = true
        onResizeVisibilityChanged(true)
    }

    fun hideResize() {
        binding.widgetResizeOutside.isVisible = false
        binding.widgetResizePanel.isVisible = false
        onResizeVisibilityChanged(false)
    }

    private fun applySize() {
        val size = prefs.homeWidgetCircleSize
        val scale = size / 112f
        circle.layoutParams = circle.layoutParams.apply {
            width = size.dpToPx()
            height = width
        }
        val padding = (12.dpToPx() * scale).roundToInt()
        circle.setPadding(padding, padding, padding, padding)
        listOf(
            binding.widgetTimeValue to 28f,
            binding.widgetAmPm to 14f,
            binding.widgetDay to 28f,
            binding.widgetDateValue to 16f,
            binding.widgetBatteryPercent to 32f,
        ).forEach { (text, baseSize) -> text.setTextSize(TypedValue.COMPLEX_UNIT_SP, baseSize * scale) }
        binding.widgetBatteryIcon.layoutParams = binding.widgetBatteryIcon.layoutParams.apply {
            width = (16.dpToPx() * scale).roundToInt()
            height = width
        }
        binding.widgetChargingGlow.layoutParams = binding.widgetChargingGlow.layoutParams.apply {
            width = (136.dpToPx() * scale).roundToInt()
            height = width
        }
    }

    private fun updateDisplay() {
        binding.widgetTime.isVisible = display == 0
        binding.widgetBattery.isVisible = display == 1
        binding.widgetDate.isVisible = display == 2
    }

    private fun minX() = safeLeft + 12.dpToPx().toFloat()
    private fun minY() = safeTop + 12.dpToPx().toFloat()
    private fun maxX() = (binding.root.width - safeRight - 12.dpToPx() - circle.width).toFloat().coerceAtLeast(minX())
    private fun maxY() = (binding.root.height - safeBottom - 12.dpToPx() - circle.height).toFloat().coerceAtLeast(minY())

    private fun place(x: Float, y: Float) {
        circle.x = x.coerceIn(minX(), maxX())
        circle.y = y.coerceIn(minY(), maxY())
        val glowOffset = (binding.widgetChargingGlow.layoutParams.width - circle.width) / 2f
        binding.widgetChargingGlow.x = circle.x - glowOffset
        binding.widgetChargingGlow.y = circle.y - glowOffset
    }

    private fun restorePosition() {
        if (binding.root.width == 0 || binding.root.height == 0 || circle.width == 0) return
        val fits = binding.root.width - safeRight - 12.dpToPx() - circle.width >= minX() &&
            binding.root.height - safeBottom - 12.dpToPx() - circle.height >= minY()
        circle.visibility = if (fits) View.VISIBLE else View.INVISIBLE
        updateChargingGlow()
        if (!fits) return
        place(
            minX() + prefs.homeWidgetCircleX * (maxX() - minX()),
            minY() + prefs.homeWidgetCircleY * (maxY() - minY()),
        )
    }

    private fun savePosition() {
        prefs.homeWidgetCircleX = if (maxX() > minX()) (circle.x - minX()) / (maxX() - minX()) else 0f
        prefs.homeWidgetCircleY = if (maxY() > minY()) (circle.y - minY()) / (maxY() - minY()) else 0f
    }

    private class ChargingGlowDrawable : Drawable() {
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        override fun draw(canvas: Canvas) {
            val radius = min(bounds.width(), bounds.height()) / 2f
            if (radius <= 0f) return
            val x = bounds.exactCenterX()
            val y = bounds.exactCenterY()
            paint.shader = RadialGradient(
                x, y, radius,
                intArrayOf(Color.TRANSPARENT, 0x0034C759, 0xCC34C759.toInt(), 0x0034C759),
                floatArrayOf(0f, 0.74f, 0.82f, 1f),
                Shader.TileMode.CLAMP,
            )
            canvas.drawCircle(x, y, radius, paint)
        }

        override fun setAlpha(alpha: Int) {
            paint.alpha = alpha
            invalidateSelf()
        }

        override fun setColorFilter(colorFilter: ColorFilter?) {
            paint.colorFilter = colorFilter
            invalidateSelf()
        }

        @Deprecated("Deprecated in Android")
        override fun getOpacity() = PixelFormat.TRANSLUCENT
    }
}
