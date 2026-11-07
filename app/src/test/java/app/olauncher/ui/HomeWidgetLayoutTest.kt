package app.olauncher.ui

import android.content.Intent
import android.os.BatteryManager
import android.os.Looper
import android.os.SystemClock
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.widget.TextClock
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
import org.robolectric.annotation.LooperMode
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

    private fun assertClearOfBar() {
        val circle = binding.widgetCircle
        assertTrue(circle.x >= 0)
        assertTrue(circle.y >= 0)
        assertTrue(circle.x + circle.width <= binding.root.width)
        assertTrue(circle.y + circle.height < binding.homeWidgetPanel.top)
    }

    @Test
    fun `bar is a search control and circle retains clock and abbreviated date formats`() {
        assertFalse(binding.widgetCommandSearch is TextClock)
        assertEquals("Command search", binding.widgetCommandSearch.text.toString())
        assertEquals(56, binding.widgetCommandSearch.layoutParams.height)
        assertEquals(binding.widgetCircle.layoutParams.width, binding.widgetCircle.layoutParams.height)
        assertEquals("hh:mm\na", binding.widgetTime.format12Hour.toString())
        assertEquals("HH:mm", binding.widgetTime.format24Hour.toString())
        assertEquals("EEE\ndd MMM", binding.widgetDate.format12Hour.toString())
        assertEquals("EEE\ndd MMM", binding.widgetDate.format24Hour.toString())
        assertClearOfBar()
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
    fun `hold drag release clamps above search bar and persists position`() {
        holdCircle()
        touch(MotionEvent.ACTION_MOVE, -100f, 1000f)
        touch(MotionEvent.ACTION_UP, -100f, 1000f)
        assertClearOfBar()
        assertEquals(0f, prefs.homeWidgetCircleX, 0f)
        assertEquals(1f, prefs.homeWidgetCircleY, 0f)
        assertEquals(View.VISIBLE, binding.widgetTime.visibility)

        val x = binding.widgetCircle.x
        val y = binding.widgetCircle.y
        setupRestoredWidget()
        assertEquals(x, binding.widgetCircle.x, 0f)
        assertEquals(y, binding.widgetCircle.y, 0f)
        layout(800, 400)
        widget.applyInsets(30, 40, 50)
        assertClearOfBar()
        assertTrue(binding.widgetCircle.x >= 42)
        assertTrue(binding.widgetCircle.y >= 52)
        assertTrue(binding.widgetCircle.x + binding.widgetCircle.width <= 738)
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
    fun `circle avoids system insets and hides if window cannot fit it above bar`() {
        widget.applyInsets(20, 30, 40)
        assertTrue(binding.widgetCircle.y >= 42)
        assertTrue(binding.widgetCircle.x + binding.widgetCircle.width <= 348)
        layout(400, 180)
        assertEquals(View.INVISIBLE, binding.widgetCircle.visibility)
        layout(400, 800)
        assertEquals(View.VISIBLE, binding.widgetCircle.visibility)
        assertClearOfBar()
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
    fun `home command bar navigates to Search Anything`() {
        prefs.firstOpen = false
        val controller = Robolectric.buildActivity(MainActivity::class.java).setup()
        try {
            val activity = controller.get()
            val navController = activity.findNavController(R.id.nav_host_fragment)
            assertEquals(R.id.mainFragment, navController.currentDestination?.id)
            activity.findViewById<View>(R.id.widgetCommandSearch).performClick()
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
            assertNull(shadowOf(activity).nextStartedActivity)
        } finally {
            controller.pause().stop().destroy()
        }
    }
}
