package com.gesturecontrol.app

import android.util.Log
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot

sealed interface GestureEvent {
    /** Cursor position in screen fractions (0..1). */
    data class Cursor(val x: Float, val y: Float, val pressed: Boolean) : GestureEvent
    data object HideCursor : GestureEvent
    data class Tap(val x: Float, val y: Float) : GestureEvent
    data class LongPress(val x: Float, val y: Float) : GestureEvent
    /** Virtual finger goes down, moves, and lifts: drag, scroll or flick. */
    data class DragStart(val x: Float, val y: Float) : GestureEvent
    data class DragMove(val x: Float, val y: Float) : GestureEvent
    data class DragEnd(val x: Float, val y: Float) : GestureEvent
    data object Back : GestureEvent
    data object Home : GestureEvent
    data object Recents : GestureEvent
}

private enum class Pose { FIST, POINT, VICTORY, THREE, PALM, OTHER }

/**
 * Turns a stream of hand landmarks into discrete phone actions.
 * Landmark indices follow MediaPipe: 0 wrist, 4 thumb tip, 8 index tip, 9 middle MCP, ...
 */
class GestureRecognizer {
    private companion object {
        const val TAG = "Gesture"

        const val PINCH_ENTER = 0.28f      // thumb-index distance relative to palm size
        const val PINCH_EXIT = 0.42f
        const val PINCH_EXIT_FRAMES = 2    // motion blur can open a pinch for a single frame
        const val TAP_MAX_MS = 600L
        const val LONG_PRESS_MS = 900L
        const val DRAG_START_DISTANCE = 0.04f // screen fraction the pinch must travel to become a drag
        const val HOLD_MS = 700L           // how long a static pose must be held
        const val CURSOR_GRACE_MS = 400L   // a pinch only counts right after pointing
        const val TAP_LOOKBACK_MS = 150L   // pinching drags the fingertip, so tap where it was
        const val LOST_FRAMES_TO_RESET = 6

        // Part of the camera frame that maps to the whole screen, so the edges are reachable
        const val AREA_LEFT = 0.15f
        const val AREA_RIGHT = 0.85f
        const val AREA_TOP = 0.10f
        const val AREA_BOTTOM = 0.75f

        // Cursor smoothing (One Euro filter): steady when the hand rests, no lag when it moves
        const val CURSOR_MIN_CUTOFF = 1.2f
        const val CURSOR_BETA = 4f
    }

    private class Sample(val t: Long, val x: Float, val y: Float)

    private val filterX = OneEuroFilter(CURSOR_MIN_CUTOFF, CURSOR_BETA)
    private val filterY = OneEuroFilter(CURSOR_MIN_CUTOFF, CURSOR_BETA)
    private var cursorX = -1f
    private var cursorY = -1f
    private var lastPointTime = 0L
    private val cursorHistory = ArrayDeque<Sample>()

    private var pinching = false
    private var pinchStart = 0L
    private var pinchX = 0f            // where the virtual finger went down
    private var pinchY = 0f
    private var pinchRefX = 0f         // thumb-index midpoint in the camera frame at pinch start
    private var pinchRefY = 0f
    private var pinchOpenFrames = 0
    private var dragging = false
    private var longPressFired = false

    private var holdPose = Pose.OTHER
    private var holdStart = 0L
    private var holdFired = false

    private var cooldownUntil = 0L
    private var lostFrames = 0
    private var cursorShown = false

    /** Human-readable state of the last frame, shown on the preview screen. */
    @Volatile
    var lastPoseLabel = "Рука не найдена"
        private set

    fun process(hand: Hand?, now: Long): List<GestureEvent> {
        val events = mutableListOf<GestureEvent>()

        if (hand == null) {
            lastPoseLabel = "Рука не найдена"
            if (++lostFrames >= LOST_FRAMES_TO_RESET) reset(events)
            return events
        }
        lostFrames = 0

        val pose = poseOf(hand)
        val pinchRatio = hand.dist(4, 8) / hand.dist(0, 9).coerceAtLeast(1e-4f)

        handlePinch(hand, pinchRatio, now, events)

        if (pinching || pose == Pose.POINT) {
            if (!pinching) updateCursor(hand, now)
            if (cursorX >= 0) {
                events += GestureEvent.Cursor(cursorX, cursorY, pinching)
                cursorShown = true
            }
        } else if (cursorShown) {
            events += GestureEvent.HideCursor
            cursorShown = false
        }

        if (!pinching) handleHold(pose, now, events)

        lastPoseLabel = when {
            dragging -> "👌 Перетаскивание"
            pinching -> "👌 Щипок"
            else -> when (pose) {
                Pose.FIST -> "✊ Кулак"
                Pose.POINT -> "☝ Указательный"
                Pose.VICTORY -> "✌ Два пальца"
                Pose.THREE -> "🤟 Три пальца"
                Pose.PALM -> "✋ Ладонь"
                Pose.OTHER -> "? Неизвестно"
            }
        }
        events.filter { it !is GestureEvent.Cursor && it !is GestureEvent.DragMove }
            .forEach { Log.d(TAG, "$pose -> $it") }
        return events
    }

    private fun handlePinch(hand: Hand, ratio: Float, now: Long, events: MutableList<GestureEvent>) {
        if (!pinching) {
            val recentlyPointing = now - lastPointTime < CURSOR_GRACE_MS && cursorX >= 0
            if (ratio < PINCH_ENTER && recentlyPointing) {
                pinching = true
                pinchStart = now
                pinchOpenFrames = 0
                dragging = false
                longPressFired = false
                val anchor = cursorHistory.lastOrNull { now - it.t >= TAP_LOOKBACK_MS }
                    ?: cursorHistory.firstOrNull()
                pinchX = anchor?.x ?: cursorX
                pinchY = anchor?.y ?: cursorY
                pinchRefX = (hand[4].x + hand[8].x) / 2
                pinchRefY = (hand[4].y + hand[8].y) / 2
                cursorX = pinchX
                cursorY = pinchY
                filterX.reset(cursorX, now)
                filterY.reset(cursorY, now)
            }
            return
        }

        if (ratio > PINCH_EXIT) {
            if (++pinchOpenFrames >= PINCH_EXIT_FRAMES) releasePinch(now, events)
            return
        }
        pinchOpenFrames = 0

        // While pinched, the finger follows how far the pinch moved, not where the fingertip is:
        // the fingertip jumps toward the thumb when pinching
        val midX = (hand[4].x + hand[8].x) / 2
        val midY = (hand[4].y + hand[8].y) / 2
        val tx = (pinchX + (midX - pinchRefX) / (AREA_RIGHT - AREA_LEFT)).coerceIn(0f, 1f)
        val ty = (pinchY + (midY - pinchRefY) / (AREA_BOTTOM - AREA_TOP)).coerceIn(0f, 1f)
        cursorX = filterX.filter(tx, now)
        cursorY = filterY.filter(ty, now)

        if (!dragging && !longPressFired && hypot(cursorX - pinchX, cursorY - pinchY) > DRAG_START_DISTANCE) {
            dragging = true
            events += GestureEvent.DragStart(pinchX, pinchY)
        }
        if (dragging) {
            events += GestureEvent.DragMove(cursorX, cursorY)
        } else if (!longPressFired && now - pinchStart >= LONG_PRESS_MS) {
            events += GestureEvent.LongPress(pinchX, pinchY)
            longPressFired = true
            cooldownUntil = now + 500
        }
    }

    private fun releasePinch(now: Long, events: MutableList<GestureEvent>) {
        pinching = false
        when {
            dragging -> events += GestureEvent.DragEnd(cursorX, cursorY)
            !longPressFired && now - pinchStart < TAP_MAX_MS && now >= cooldownUntil -> {
                events += GestureEvent.Tap(pinchX, pinchY)
                cooldownUntil = now + 300
            }
        }
        dragging = false
        // Keep the cursor alive so the user can point again without a gap
        lastPointTime = now
        cursorHistory.clear()
        filterX.reset(cursorX, now)
        filterY.reset(cursorY, now)
    }

    private fun updateCursor(hand: Hand, now: Long) {
        val tip = hand[8]
        val tx = ((tip.x - AREA_LEFT) / (AREA_RIGHT - AREA_LEFT)).coerceIn(0f, 1f)
        val ty = ((tip.y - AREA_TOP) / (AREA_BOTTOM - AREA_TOP)).coerceIn(0f, 1f)
        if (cursorX < 0 || now - lastPointTime > CURSOR_GRACE_MS) {
            filterX.reset(tx, now)
            filterY.reset(ty, now)
        }
        cursorX = filterX.filter(tx, now)
        cursorY = filterY.filter(ty, now)
        lastPointTime = now
        cursorHistory.addLast(Sample(now, cursorX, cursorY))
        while (cursorHistory.size > 1 && now - cursorHistory.first().t > 500) cursorHistory.removeFirst()
    }

    private fun handleHold(pose: Pose, now: Long, events: MutableList<GestureEvent>) {
        if (pose != holdPose) {
            holdPose = pose
            holdStart = now
            holdFired = false
            return
        }
        val action = when (pose) {
            Pose.FIST -> GestureEvent.Back
            Pose.VICTORY -> GestureEvent.Home
            Pose.THREE -> GestureEvent.Recents
            else -> null
        }
        if (action != null && !holdFired && now - holdStart >= HOLD_MS && now >= cooldownUntil) {
            events += action
            holdFired = true
            cooldownUntil = now + 800
        }
    }

    private fun poseOf(hand: Hand): Pose {
        // A finger is extended when its tip is clearly farther from the wrist than its middle joint
        fun extended(tip: Int, pip: Int) = hand.dist(0, tip) > hand.dist(0, pip) * 1.1f
        val index = extended(8, 6)
        val middle = extended(12, 10)
        val ring = extended(16, 14)
        val pinky = extended(20, 18)
        return when {
            !index && !middle && !ring && !pinky -> Pose.FIST
            index && !middle && !ring && !pinky -> Pose.POINT
            index && middle && !ring && !pinky -> Pose.VICTORY
            index && middle && ring && !pinky -> Pose.THREE
            index && middle && ring && pinky -> Pose.PALM
            else -> Pose.OTHER
        }
    }

    private fun reset(events: MutableList<GestureEvent>) {
        // Never leave the virtual finger pressed when the hand disappears
        if (dragging) events += GestureEvent.DragEnd(cursorX, cursorY)
        if (cursorShown) events += GestureEvent.HideCursor
        cursorShown = false
        pinching = false
        dragging = false
        cursorX = -1f
        cursorY = -1f
        cursorHistory.clear()
        holdPose = Pose.OTHER
    }
}

/** One Euro filter (Casiez et al.): adapts smoothing to speed, removing jitter without adding lag. */
private class OneEuroFilter(private val minCutoff: Float, private val beta: Float) {
    private val dCutoff = 1f
    private var x = 0f
    private var dx = 0f
    private var lastTime = -1L

    fun reset(value: Float, now: Long) {
        x = value
        dx = 0f
        lastTime = now
    }

    fun filter(value: Float, now: Long): Float {
        if (lastTime < 0) {
            reset(value, now)
            return value
        }
        val dt = ((now - lastTime) / 1000f).coerceAtLeast(1e-3f)
        lastTime = now
        val rawDx = (value - x) / dt
        dx += (rawDx - dx) * alpha(dCutoff, dt)
        val cutoff = minCutoff + beta * abs(dx)
        x += (value - x) * alpha(cutoff, dt)
        return x
    }

    private fun alpha(cutoff: Float, dt: Float): Float {
        val tau = 1f / (2f * PI.toFloat() * cutoff)
        return 1f / (1f + tau / dt)
    }
}
