package app.olauncher.ui

import android.view.GestureDetector
import android.view.MotionEvent
import android.view.View
import androidx.core.view.ViewCompat
import androidx.core.view.accessibility.AccessibilityNodeInfoCompat
import androidx.core.view.isVisible
import app.olauncher.R
import app.olauncher.data.Prefs
import app.olauncher.databinding.LayoutHomeWidgetBinding
import app.olauncher.helper.dpToPx

class HomeWidget(
    private val binding: LayoutHomeWidgetBinding,
    private val prefs: Prefs,
) {
    private val circle = binding.widgetCircle
    private var display = 0
    private var dragging = false
    private var downX = 0f
    private var downY = 0f
    private var startX = 0f
    private var startY = 0f
    private var safeLeft = 0
    private var safeTop = 0
    private var safeRight = 0
    private val detector = GestureDetector(circle.context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true

        override fun onSingleTapUp(event: MotionEvent): Boolean {
            circle.performClick()
            return true
        }

        override fun onLongPress(event: MotionEvent) {
            circle.performLongClick()
        }
    })

    init {
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
        binding.homeWidgetPanel.addOnLayoutChangeListener { _, _, _, _, _, _, _, _, _ -> restorePosition() }
        updateDisplay()
    }

    fun applyInsets(left: Int, top: Int, right: Int) {
        safeLeft = left
        safeTop = top
        safeRight = right
        restorePosition()
    }

    private fun updateDisplay() {
        binding.widgetTime.isVisible = display == 0
        binding.widgetBattery.isVisible = display == 1
        binding.widgetDate.isVisible = display == 2
    }

    private fun minX() = safeLeft + 12.dpToPx().toFloat()
    private fun minY() = safeTop + 12.dpToPx().toFloat()
    private fun maxX() = (binding.root.width - safeRight - 12.dpToPx() - circle.width).toFloat().coerceAtLeast(minX())
    private fun maxY() = (binding.homeWidgetPanel.top - 12.dpToPx() - circle.height).toFloat().coerceAtLeast(minY())

    private fun place(x: Float, y: Float) {
        circle.x = x.coerceIn(minX(), maxX())
        circle.y = y.coerceIn(minY(), maxY())
    }

    private fun restorePosition() {
        if (binding.root.width == 0 || circle.width == 0 || binding.homeWidgetPanel.top == 0) return
        val fits = binding.root.width - safeRight - 12.dpToPx() - circle.width >= minX() &&
            binding.homeWidgetPanel.top - 12.dpToPx() - circle.height >= minY()
        circle.visibility = if (fits) View.VISIBLE else View.INVISIBLE
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
}
