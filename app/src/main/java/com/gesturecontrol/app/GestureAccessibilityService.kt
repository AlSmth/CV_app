package com.gesturecontrol.app

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PixelFormat
import android.os.Build
import android.os.SystemClock
import android.util.DisplayMetrics
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent

/** Performs taps, swipes and global actions on behalf of the camera service. */
class GestureAccessibilityService : AccessibilityService() {

    companion object {
        @Volatile
        var instance: GestureAccessibilityService? = null
            private set
    }

    private lateinit var windowManager: WindowManager
    private var cursorView: CursorView? = null

    override fun onServiceConnected() {
        windowManager = getSystemService(WindowManager::class.java)
        addCursorOverlay()
        instance = this
        AppState.accessibilityEnabled.value = true
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) = Unit

    override fun onInterrupt() = Unit

    override fun onDestroy() {
        instance = null
        AppState.accessibilityEnabled.value = false
        cursorView?.let { windowManager.removeView(it) }
        cursorView = null
        super.onDestroy()
    }

    /** Must be called on the main thread. */
    fun handle(event: GestureEvent) {
        val (w, h) = screenSize()
        when (event) {
            is GestureEvent.Cursor -> cursorView?.show(event.x * w, event.y * h, event.pressed)
            GestureEvent.HideCursor -> cursorView?.hide()
            is GestureEvent.Tap -> {
                cursorView?.hide() // keep the overlay out of the way of the tap
                tap(event.x * w, event.y * h, 50)
            }
            is GestureEvent.LongPress -> tap(event.x * w, event.y * h, 800)
            is GestureEvent.DragStart -> drag.start(event.x * w, event.y * h)
            is GestureEvent.DragMove -> drag.moveTo(event.x * w, event.y * h, end = false)
            is GestureEvent.DragEnd -> drag.moveTo(event.x * w, event.y * h, end = true)
            GestureEvent.Back -> performGlobalAction(GLOBAL_ACTION_BACK)
            GestureEvent.Home -> performGlobalAction(GLOBAL_ACTION_HOME)
            GestureEvent.Recents -> performGlobalAction(GLOBAL_ACTION_RECENTS)
        }
    }

    fun hideCursor() = cursorView?.hide()

    private fun tap(x: Float, y: Float, durationMs: Long) {
        val path = Path().apply { moveTo(x, y) }
        dispatch(path, durationMs)
    }

    private val drag = Drag()

    /**
     * A finger that stays down across frames. Android plays a gesture only after the previous
     * piece finished, so new positions wait in [pendingX]/[pendingY] while a piece is running.
     */
    private inner class Drag : GestureResultCallback() {
        private var stroke: GestureDescription.StrokeDescription? = null
        private var lastX = 0f
        private var lastY = 0f
        private var lastDispatch = 0L
        private var busy = false
        private var pendingX = Float.NaN
        private var pendingY = Float.NaN
        private var pendingEnd = false

        fun start(x: Float, y: Float) {
            val path = Path().apply { moveTo(x, y) }
            val first = GestureDescription.StrokeDescription(path, 0, 10, true)
            stroke = first
            lastX = x
            lastY = y
            pendingX = Float.NaN
            pendingEnd = false
            send(first)
        }

        fun moveTo(x: Float, y: Float, end: Boolean) {
            if (stroke == null) return
            pendingX = x
            pendingY = y
            pendingEnd = pendingEnd || end
            if (!busy) flush()
        }

        private fun flush() {
            val current = stroke ?: return
            if (pendingX.isNaN() && !pendingEnd) return
            val toX = if (pendingX.isNaN()) lastX else pendingX
            val toY = if (pendingX.isNaN()) lastY else pendingY
            val end = pendingEnd
            pendingX = Float.NaN
            pendingEnd = false

            // A path needs some length; a 1px nudge is invisible
            val path = Path().apply {
                moveTo(lastX, lastY)
                if (toX == lastX && toY == lastY) lineTo(toX + 1, toY) else lineTo(toX, toY)
            }
            // Play each piece as long as it really took, so lists get the hand's real speed and fling on release
            val now = SystemClock.uptimeMillis()
            val duration = (now - lastDispatch).coerceIn(8L, 60L)
            val next = current.continueStroke(path, 0, duration, !end)
            stroke = if (end) null else next
            lastX = toX
            lastY = toY
            send(next)
        }

        private var inFlight: GestureDescription? = null

        private fun send(piece: GestureDescription.StrokeDescription) {
            busy = true
            lastDispatch = SystemClock.uptimeMillis()
            val gesture = GestureDescription.Builder().addStroke(piece).build()
            inFlight = gesture
            if (!dispatchGesture(gesture, this, null)) onCancelled(gesture)
        }

        override fun onCompleted(gestureDescription: GestureDescription?) {
            if (gestureDescription !== inFlight) return // a piece of an older drag
            busy = false
            flush()
        }

        override fun onCancelled(gestureDescription: GestureDescription?) {
            if (gestureDescription !== inFlight) return
            // The system interrupted the finger (e.g. a real touch); drop this drag
            busy = false
            stroke = null
        }
    }

    private fun dispatch(path: Path, durationMs: Long) {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        dispatchGesture(gesture, null, null)
    }

    private fun screenSize(): Pair<Int, Int> {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            val bounds = windowManager.maximumWindowMetrics.bounds
            return bounds.width() to bounds.height()
        }
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        windowManager.defaultDisplay.getRealMetrics(metrics)
        return metrics.widthPixels to metrics.heightPixels
    }

    private fun addCursorOverlay() {
        val view = CursorView(this)
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) {
            params.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }
        windowManager.addView(view, params)
        cursorView = view
    }

    /** Transparent full-screen overlay that only draws the pointer; touches pass through it. */
    private class CursorView(context: Context) : View(context) {
        private val radius = 18f * resources.displayMetrics.density
        private val fill = Paint(Paint.ANTI_ALIAS_FLAG)
        private val ring = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = 3f * resources.displayMetrics.density
            color = Color.WHITE
        }
        private var cx = 0f
        private var cy = 0f
        private var pressed = false
        private var visible = false

        fun show(x: Float, y: Float, pressed: Boolean) {
            cx = x
            cy = y
            this.pressed = pressed
            visible = true
            invalidate()
        }

        fun hide() {
            if (!visible) return
            visible = false
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            if (!visible) return
            // Overlay may be offset by system bars on some devices; draw in screen coordinates
            val loc = IntArray(2).also { getLocationOnScreen(it) }
            val x = cx - loc[0]
            val y = cy - loc[1]
            fill.color = if (pressed) 0xCCFF5722.toInt() else 0x993F51B5.toInt()
            canvas.drawCircle(x, y, if (pressed) radius * 0.7f else radius, fill)
            canvas.drawCircle(x, y, radius, ring)
        }
    }
}
