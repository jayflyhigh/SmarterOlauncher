package app.olauncher.listener

import android.os.Looper
import android.os.SystemClock
import android.view.MotionEvent
import android.view.View
import android.view.ViewConfiguration
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [24, 28])
@LooperMode(LooperMode.Mode.PAUSED)
class OnSwipeTouchListenerTest {
    private lateinit var view: View
    private lateinit var listener: OnSwipeTouchListener
    private var longClicks = 0
    private var clicks = 0
    private var swipes = 0
    private var downTime = 0L
    private val holdDuration
        get() = (ViewConfiguration.getLongPressTimeout() + ViewConfiguration.getTapTimeout()).toLong()

    @Before
    fun setup() {
        val context = RuntimeEnvironment.getApplication()
        view = View(context)
        listener = object : OnSwipeTouchListener(context) {
            override fun onLongClick() {
                longClicks++
            }

            override fun onClick() {
                clicks++
            }

            override fun onSwipeUp() {
                swipes++
            }
        }
    }

    private fun touch(action: Int, y: Float = 400f) {
        if (action == MotionEvent.ACTION_DOWN) downTime = SystemClock.uptimeMillis()
        val event = MotionEvent.obtain(downTime, SystemClock.uptimeMillis(), action, 200f, y, 0)
        try {
            listener.onTouch(view, event)
        } finally {
            event.recycle()
        }
    }

    private fun advance(millis: Long) {
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(millis))
    }

    @Test
    fun `long press fires by the system timeout without an extra delay and only once`() {
        touch(MotionEvent.ACTION_DOWN)
        advance(ViewConfiguration.getTapTimeout().toLong())
        assertEquals(0, longClicks)
        advance(ViewConfiguration.getLongPressTimeout().toLong())
        assertEquals(1, longClicks)
        advance(1000)
        touch(MotionEvent.ACTION_UP)
        assertEquals(1, longClicks)
        assertEquals(0, clicks)
        assertEquals(0, swipes)
    }

    @Test
    fun `releasing a short tap does not open settings later`() {
        touch(MotionEvent.ACTION_DOWN)
        advance(50)
        touch(MotionEvent.ACTION_UP)
        advance(holdDuration + 1000)
        assertEquals(0, longClicks)
        assertEquals(1, clicks)
    }

    @Test
    fun `cancelling a hold does not open settings later`() {
        touch(MotionEvent.ACTION_DOWN)
        advance(50)
        touch(MotionEvent.ACTION_CANCEL)
        advance(holdDuration + 1000)
        assertEquals(0, longClicks)
        assertEquals(0, clicks)
    }

    @Test
    fun `swiping still works without opening settings`() {
        touch(MotionEvent.ACTION_DOWN)
        advance(20)
        touch(MotionEvent.ACTION_MOVE, 250f)
        advance(20)
        touch(MotionEvent.ACTION_UP, 100f)
        advance(holdDuration + 1000)
        assertEquals(1, swipes)
        assertEquals(0, longClicks)
        assertEquals(0, clicks)
    }
}
