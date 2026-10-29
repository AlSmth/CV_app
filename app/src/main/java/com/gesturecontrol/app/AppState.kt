package com.gesturecontrol.app

import android.graphics.Bitmap
import kotlinx.coroutines.flow.MutableStateFlow

/** One analyzed camera frame for the preview screen. */
class PreviewFrame(val bitmap: Bitmap, val hand: Hand?, val pose: String, val fps: Float)

/** Shared state between the services and the screens. */
object AppState {
    val cameraRunning = MutableStateFlow(false)
    val accessibilityEnabled = MutableStateFlow(false)
    val lastGesture = MutableStateFlow("—")

    /** Set while the preview screen is open; gestures are then shown but not performed. Called off the main thread. */
    @Volatile
    var previewListener: ((PreviewFrame) -> Unit)? = null

    fun describe(event: GestureEvent): String? = when (event) {
        is GestureEvent.Tap -> "👆 Нажатие"
        is GestureEvent.LongPress -> "👆 Долгое нажатие"
        is GestureEvent.DragStart -> "👌 Перетаскивание…"
        is GestureEvent.DragEnd -> "👌 Перелистывание"
        GestureEvent.Back -> "✊ Назад"
        GestureEvent.Home -> "✌ Домой"
        GestureEvent.Recents -> "🤟 Недавние"
        is GestureEvent.Cursor, GestureEvent.HideCursor, is GestureEvent.DragMove -> null
    }
}
