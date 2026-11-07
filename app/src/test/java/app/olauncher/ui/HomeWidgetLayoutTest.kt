package app.olauncher.ui

import android.app.Activity
import android.animation.ValueAnimator
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.BatteryManager
import android.os.Looper
import android.os.SystemClock
import android.provider.Settings
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.appcompat.view.ContextThemeWrapper
import androidx.navigation.findNavController
import app.olauncher.MainActivity
import app.olauncher.R
import app.olauncher.data.Constants
import app.olauncher.data.Prefs
import app.olauncher.data.SearchMode
import app.olauncher.databinding.LayoutHomeWidgetBinding
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 28])
@LooperMode(LooperMode.Mode.PAUSED)
class HomeWidgetLayoutTest {
    private lateinit var binding: LayoutHomeWidgetBinding
    private lateinit var prefs: Prefs
    private lateinit var widget: HomeWidget
    private var downTime = 0L

    @Before
    fun setup() {
        val context = ContextThemeWrapper(RuntimeEnvironment.getApplication(), R.style.AppTheme)
        context.getSharedPreferences("app.olauncher", 0).edit().clear().commit()
        prefs = Prefs(context)
        binding = LayoutHomeWidgetBinding.inflate(LayoutInflater.from(context))
        widget = HomeWidget(binding, prefs)
        layout(400, 800)
    }

    private fun layout(width: Int, height: Int) {
        binding.root.measure(
            View.MeasureSpec.makeMeasureSpec(width, View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(height, View.MeasureSpec.EXACTLY),
        )
        binding.root.layout(0, 0, width, height)
    }

    private fun touch(action: Int, x: Float, y: Float) {
        if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, x, y, 0)
        try {
            assertTrue(binding.root.dispatchTouchEvent(event))
        } finally {
            event.recycle()
        }
    }

    private fun holdCircle() {
        touch(MotionEvent.ACTION_DOWN, binding.widgetCircle.x + 56, binding.widgetCircle.y + 56)
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
    }

    private fun assertWithinScreen() {
        val circle = binding.widgetCircle
        assertTrue(circle.x >= 0)
        assertTrue(circle.y >= 0)
        assertTrue(circle.x + circle.width <= binding.root.width)
        assertTrue(circle.y + circle.height <= binding.root.height)
    }

    @Test
    fun `widget has circle and glow with resize controls hidden and retains clock and abbreviated date formats`() {
        assertEquals(View.GONE, binding.widgetResizePanel.visibility)
        assertEquals(View.GONE, binding.widgetResizeOutside.visibility)
        assertEquals(binding.widgetCircle.layoutParams.width, binding.widgetCircle.layoutParams.height)
        assertEquals("hh:mm", binding.widgetTimeValue.format12Hour.toString())
        assertEquals("HH:mm", binding.widgetTimeValue.format24Hour.toString())
        assertEquals("a", binding.widgetAmPm.format12Hour.toString())
        assertEquals("", binding.widgetAmPm.format24Hour.toString())
        assertEquals("EEE", binding.widgetDay.format12Hour.toString())
        assertEquals("EEE", binding.widgetDay.format24Hour.toString())
        assertEquals("dd MMM", binding.widgetDateValue.format12Hour.toString())
        assertEquals("dd MMM", binding.widgetDateValue.format24Hour.toString())
        assertWithinScreen()
    }

    private fun swipeUpCircle() {
        val x = binding.widgetCircle.x + binding.widgetCircle.width / 2
        val y = binding.widgetCircle.y + binding.widgetCircle.height / 2
        touch(MotionEvent.ACTION_DOWN, x, y)
        touch(MotionEvent.ACTION_MOVE, x, y - 80)
        touch(MotionEvent.ACTION_UP, x, y - 80)
    }

    @Test
    fun `swipe up opens bottom slider without cycling or dragging and dismisses outside or Done`() {
        val x = binding.widgetCircle.x
        val y = binding.widgetCircle.y
        swipeUpCircle()
        layout(400, 800)
        assertEquals(View.VISIBLE, binding.widgetResizePanel.visibility)
        assertEquals(64, binding.widgetResizePanel.height)
        assertEquals(48, binding.widgetSizeSlider.height)
        assertEquals(48, binding.widgetResizeDone.height)
        assertEquals(binding.widgetSizeSlider.top, binding.widgetResizeDone.top)
        assertTrue(binding.widgetSizeSlider.right < binding.widgetResizeDone.left)
        assertEquals(View.VISIBLE, binding.widgetTime.visibility)
        assertEquals(x, binding.widgetCircle.x, 0f)
        assertEquals(y, binding.widgetCircle.y, 0f)
        widget.applyInsets(20, 30, 40, 50)
        layout(400, 800)
        assertEquals(738, binding.widgetResizePanel.bottom)
        assertEquals(32, binding.widgetResizePanel.left)
        assertEquals(348, binding.widgetResizePanel.right)
        binding.widgetResizeDone.performClick()
        assertEquals(View.GONE, binding.widgetResizePanel.visibility)
        swipeUpCircle()
        binding.widgetResizeOutside.performClick()
        assertEquals(View.GONE, binding.widgetResizePanel.visibility)
    }

    @Test
    fun `slider scales every reading icon padding and glow at both endpoints and persists size`() {
        swipeUpCircle()
        for (size in listOf(56, 280, 112)) {
            binding.widgetSizeSlider.progress = size - 56
            layout(400, 800)
            val scale = size / 112f
            assertEquals(size, binding.widgetCircle.width)
            assertEquals(size, binding.widgetCircle.height)
            assertEquals(28f * scale, binding.widgetTimeValue.textSize, 0.01f)
            assertEquals(14f * scale, binding.widgetAmPm.textSize, 0.01f)
            assertEquals(28f * scale, binding.widgetDay.textSize, 0.01f)
            assertEquals(16f * scale, binding.widgetDateValue.textSize, 0.01f)
            assertEquals(32f * scale, binding.widgetBatteryPercent.textSize, 0.01f)
            assertEquals((16 * scale).toInt(), binding.widgetBatteryIcon.layoutParams.width)
            assertEquals((12 * scale).toInt(), binding.widgetCircle.paddingTop)
            assertEquals((136 * scale).toInt(), binding.widgetChargingGlow.layoutParams.width)
            assertEquals(size, Prefs(binding.root.context).homeWidgetCircleSize)
            assertWithinScreen()
        }
        binding.widgetSizeSlider.progress = 224
        setupRestoredWidget()
        assertEquals(280, binding.widgetCircle.width)
        assertEquals(224, binding.widgetSizeSlider.progress)
        assertWithinScreen()
        widget.applyInsets(20, 30, 40, 50)
        layout(400, 350)
        assertEquals(View.INVISIBLE, binding.widgetCircle.visibility)
        layout(400, 800)
        assertEquals(View.VISIBLE, binding.widgetCircle.visibility)
    }

    @Test
    fun `leaving home dismisses resize popup`() {
        swipeUpCircle()
        widget.setActive(false)
        assertEquals(View.GONE, binding.widgetResizePanel.visibility)
    }

    @Test
    fun `Back dismisses resize popup without leaving home`() {
        prefs.firstOpen = false
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val circle = activity.findViewById<View>(R.id.widgetCircle)
            val root = circle.parent as ViewGroup
            root.measure(
                View.MeasureSpec.makeMeasureSpec(400, View.MeasureSpec.EXACTLY),
                View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY),
            )
            root.layout(0, 0, 400, 800)
            val x = circle.x + circle.width / 2
            val y = circle.y + circle.height / 2
            val down = SystemClock.uptimeMillis()
            for ((action, offset) in listOf(MotionEvent.ACTION_DOWN to 0f, MotionEvent.ACTION_MOVE to -80f, MotionEvent.ACTION_UP to -80f)) {
                val event = MotionEvent.obtain(down, down, action, x, y + offset, 0)
                root.dispatchTouchEvent(event)
                event.recycle()
            }
            val panel = activity.findViewById<View>(R.id.widgetResizePanel)
            assertEquals(View.VISIBLE, panel.visibility)
            activity.onBackPressedDispatcher.onBackPressed()
            assertEquals(View.GONE, panel.visibility)
            assertEquals(R.id.mainFragment, activity.findNavController(R.id.nav_host_fragment).currentDestination?.id)
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `primary readings are larger with secondary details underneath`() {
        assertEquals(28f, binding.widgetTimeValue.textSize, 0f)
        assertEquals(14f, binding.widgetAmPm.textSize, 0f)
        assertEquals(28f, binding.widgetDay.textSize, 0f)
        assertEquals(16f, binding.widgetDateValue.textSize, 0f)
        assertEquals(32f, binding.widgetBatteryPercent.textSize, 0f)
        assertEquals(16, binding.widgetBatteryIcon.layoutParams.width)
        assertEquals(16, binding.widgetBatteryIcon.layoutParams.height)
        assertTrue(binding.widgetAmPm.top >= binding.widgetTimeValue.bottom)
        binding.widgetCircle.performClick()
        binding.widgetCircle.performClick()
        layout(400, 800)
        assertTrue(binding.widgetDateValue.top >= binding.widgetDay.bottom)
    }

    @Test
    fun `AM PM is hidden for 24 hour time and restored for 12 hour time`() {
        val resolver = binding.root.context.contentResolver
        val original = Settings.System.getString(resolver, Settings.System.TIME_12_24)
        try {
            Settings.System.putString(resolver, Settings.System.TIME_12_24, "24")
            widget.setActive(true)
            assertEquals(View.GONE, binding.widgetAmPm.visibility)
            Settings.System.putString(resolver, Settings.System.TIME_12_24, "12")
            widget.setActive(true)
            assertEquals(View.VISIBLE, binding.widgetAmPm.visibility)
        } finally {
            Settings.System.putString(resolver, Settings.System.TIME_12_24, original)
        }
    }

    @Test
    fun `green charging halo pulses in every display and stops when inactive or unplugged`() {
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            controller.get().setContentView(binding.root)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(32))
            layout(400, 800)
            widget.setActive(true)
            widget.setCharging(true)
            val glow = binding.widgetChargingGlow
            assertEquals(View.VISIBLE, glow.visibility)
            val animator = ReflectionHelpers.getField<ValueAnimator>(widget, "pulse")
            assertEquals(ValueAnimator.INFINITE, shadowOf(animator).actualRepeatCount)
            assertEquals(ValueAnimator.REVERSE, animator.repeatMode)
            assertEquals(1400L, animator.duration)
            val initialAlpha = glow.alpha
            animator.currentPlayTime = 700
            assertTrue("Pulse alpha: $initialAlpha -> ${glow.alpha}", glow.alpha > initialAlpha)
            animator.currentPlayTime = 1400
            assertEquals(1f, glow.alpha, 0.01f)
            animator.currentPlayTime = 2100
            assertTrue(glow.alpha < 1f)
            repeat(3) {
                binding.widgetCircle.performClick()
                assertEquals(View.VISIBLE, glow.visibility)
            }

            assertEquals(binding.widgetCircle.x - 12, glow.x, 0f)
            assertEquals(binding.widgetCircle.y - 12, glow.y, 0f)

            widget.setActive(false)
            assertEquals(View.GONE, glow.visibility)
            assertEquals(1f, glow.alpha, 0f)
            assertFalse(animator.isStarted)
            widget.setActive(true)
            assertEquals(View.VISIBLE, glow.visibility)
            widget.setCharging(false)
            assertEquals(View.GONE, glow.visibility)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
            assertEquals(1f, glow.alpha, 0f)
            widget.setCharging(true)
            controller.get().setContentView(View(controller.get()))
            assertEquals(1f, glow.alpha, 0f)
        } finally {
            widget.setActive(false)
            controller.pause().stop().destroy()
        }
    }

    @Test
    @Config(sdk = [28])
    @GraphicsMode(GraphicsMode.Mode.NATIVE)
    fun `charging halo renders green at the circle edge and transparent in the center`() {
        val bitmap = Bitmap.createBitmap(136, 136, Bitmap.Config.ARGB_8888)
        binding.widgetChargingGlow.background.setBounds(0, 0, 136, 136)
        binding.widgetChargingGlow.background.draw(Canvas(bitmap))
        val edge = bitmap.getPixel(124, 68)
        assertTrue(Color.alpha(edge) > 0)
        assertTrue(Color.green(edge) > Color.red(edge))
        assertTrue(Color.green(edge) > Color.blue(edge))
        assertEquals(0, Color.alpha(bitmap.getPixel(68, 68)))
    }

    @Test
    fun `charging halo stays steady when system animations are disabled`() {
        val resolver = binding.root.context.contentResolver
        val original = Settings.Global.getFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 1f)
        val controller = Robolectric.buildActivity(Activity::class.java).setup()
        try {
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, 0f)
            controller.get().setContentView(binding.root)
            layout(400, 800)
            widget.setActive(true)
            widget.setCharging(true)
            assertEquals(View.VISIBLE, binding.widgetChargingGlow.visibility)
            assertEquals(1f, binding.widgetChargingGlow.alpha, 0f)
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(700))
            assertEquals(1f, binding.widgetChargingGlow.alpha, 0f)
        } finally {
            widget.setActive(false)
            Settings.Global.putFloat(resolver, Settings.Global.ANIMATOR_DURATION_SCALE, original)
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `taps cycle time battery date and back without launching apps`() {
        val circle = binding.widgetCircle
        val x = circle.x + 56
        val y = circle.y + 56
        assertEquals(View.VISIBLE, binding.widgetTime.visibility)
        for (expected in listOf(binding.widgetBattery, binding.widgetDate, binding.widgetTime)) {
            touch(MotionEvent.ACTION_DOWN, x, y)
            touch(MotionEvent.ACTION_UP, x, y)
            assertEquals(View.VISIBLE, expected.visibility)
            assertEquals(1, listOf(binding.widgetTime, binding.widgetBattery, binding.widgetDate).count {
                it.visibility == View.VISIBLE
            })
        }
        assertNull(shadowOf(RuntimeEnvironment.getApplication()).nextStartedActivity)
    }

    @Test
    fun `hold drag release uses full screen height and persists position`() {
        holdCircle()
        touch(MotionEvent.ACTION_MOVE, -100f, 1000f)
        touch(MotionEvent.ACTION_UP, -100f, 1000f)
        assertWithinScreen()
        assertEquals(788f, binding.widgetCircle.y + binding.widgetCircle.height, 0f)
        assertEquals(0f, prefs.homeWidgetCircleX, 0f)
        assertEquals(1f, prefs.homeWidgetCircleY, 0f)
        assertEquals(View.VISIBLE, binding.widgetTime.visibility)

        val x = binding.widgetCircle.x
        val y = binding.widgetCircle.y
        setupRestoredWidget()
        assertEquals(x, binding.widgetCircle.x, 0f)
        assertEquals(y, binding.widgetCircle.y, 0f)
        layout(800, 400)
        widget.applyInsets(30, 40, 50, 60)
        assertWithinScreen()
        assertTrue(binding.widgetCircle.x >= 42)
        assertTrue(binding.widgetCircle.y >= 52)
        assertTrue(binding.widgetCircle.x + binding.widgetCircle.width <= 738)
        assertEquals(328f, binding.widgetCircle.y + binding.widgetCircle.height, 0f)
    }

    private fun setupRestoredWidget() {
        val context = binding.root.context
        binding = LayoutHomeWidgetBinding.inflate(LayoutInflater.from(context))
        prefs = Prefs(context)
        widget = HomeWidget(binding, prefs)
        layout(400, 800)
    }

    @Test
    fun `movement without holding neither drags nor cycles`() {
        val x = binding.widgetCircle.x
        val y = binding.widgetCircle.y
        touch(MotionEvent.ACTION_DOWN, x + 56, y + 56)
        touch(MotionEvent.ACTION_MOVE, x - 100, y + 300)
        touch(MotionEvent.ACTION_UP, x - 100, y + 300)
        assertEquals(x, binding.widgetCircle.x, 0f)
        assertEquals(y, binding.widgetCircle.y, 0f)
        assertEquals(View.VISIBLE, binding.widgetTime.visibility)
    }

    @Test
    fun `cancelled drag restores saved placement`() {
        val x = binding.widgetCircle.x
        val y = binding.widgetCircle.y
        holdCircle()
        touch(MotionEvent.ACTION_MOVE, 20f, 400f)
        touch(MotionEvent.ACTION_CANCEL, 20f, 400f)
        assertEquals(x, binding.widgetCircle.x, 0f)
        assertEquals(y, binding.widgetCircle.y, 0f)
        assertEquals(1f, prefs.homeWidgetCircleX, 0f)
        assertEquals(0f, prefs.homeWidgetCircleY, 0f)
    }

    @Test
    fun `circle avoids system insets and hides if window cannot fit it`() {
        widget.applyInsets(20, 30, 40, 50)
        assertTrue(binding.widgetCircle.y >= 42)
        assertTrue(binding.widgetCircle.x + binding.widgetCircle.width <= 348)
        layout(400, 180)
        assertEquals(View.INVISIBLE, binding.widgetCircle.visibility)
        layout(400, 800)
        assertEquals(View.VISIBLE, binding.widgetCircle.visibility)
        assertWithinScreen()
    }

    @Test
    fun `custom battery app selection remains persisted`() {
        prefs.batteryAppPackage = "example.battery"
        prefs.batteryAppClassName = "example.battery.Settings"
        prefs.batteryAppUser = "owner"

        val restored = Prefs(binding.root.context)
        assertEquals("example.battery", restored.batteryAppPackage)
        assertEquals("example.battery.Settings", restored.batteryAppClassName)
        assertEquals("owner", restored.batteryAppUser)
    }

    @Test
    fun `home reserves no space for search bar and ACES menu still opens Search Anything`() {
        prefs.firstOpen = false
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val navController = activity.findNavController(R.id.nav_host_fragment)
            assertEquals(R.id.mainFragment, navController.currentDestination?.id)
            val apps = activity.findViewById<View>(R.id.homeAppsLayout)
            assertEquals(0, (apps.layoutParams as ViewGroup.MarginLayoutParams).bottomMargin)
            activity.findViewById<View>(R.id.searchAll).performClick()
            assertEquals(R.id.universalSearchFragment, navController.currentDestination?.id)
            assertEquals(
                SearchMode.ALL_SEARCH.name,
                navController.currentBackStackEntry?.arguments?.getString(Constants.Key.SEARCH_MODE),
            )
        } finally {
            controller.pause().stop().destroy()
        }
    }

    @Test
    fun `home circle receives live battery level and charging status without opening apps`() {
        prefs.firstOpen = false
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.sendBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED).apply {
                putExtra(BatteryManager.EXTRA_LEVEL, 85)
                putExtra(BatteryManager.EXTRA_SCALE, 100)
                putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_CHARGING)
            })
            shadowOf(Looper.getMainLooper()).idle()
            activity.findViewById<View>(R.id.widgetCircle).performClick()
            val battery = activity.findViewById<View>(R.id.widgetBattery)
            assertEquals(View.VISIBLE, battery.visibility)
            assertEquals("85% battery, charging", battery.contentDescription.toString())
            assertEquals("85%", activity.findViewById<android.widget.TextView>(R.id.widgetBatteryPercent).text.toString())
            val glow = activity.findViewById<View>(R.id.widgetChargingGlow)
            assertEquals(View.VISIBLE, glow.visibility)
            activity.sendBroadcast(Intent(Intent.ACTION_BATTERY_CHANGED).apply {
                putExtra(BatteryManager.EXTRA_LEVEL, 84)
                putExtra(BatteryManager.EXTRA_SCALE, 100)
                putExtra(BatteryManager.EXTRA_STATUS, BatteryManager.BATTERY_STATUS_DISCHARGING)
            })
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(View.GONE, glow.visibility)
            assertEquals("84% battery", battery.contentDescription.toString())
            assertNull(shadowOf(activity).nextStartedActivity)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
