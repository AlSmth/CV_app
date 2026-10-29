package com.gesturecontrol.app

import android.content.Context
import android.graphics.Bitmap
import android.os.SystemClock
import android.util.Log
import com.google.mediapipe.framework.image.BitmapImageBuilder
import com.google.mediapipe.tasks.core.BaseOptions
import com.google.mediapipe.tasks.core.Delegate
import com.google.mediapipe.tasks.vision.core.RunningMode
import com.google.mediapipe.tasks.vision.handlandmarker.HandLandmarker
import java.io.Closeable
import kotlin.math.hypot

/** 21 hand landmarks in normalized image coordinates (0..1), already mirrored like a selfie. */
class Hand(val points: List<Pt>, val aspect: Float) {
    data class Pt(val x: Float, val y: Float)

    operator fun get(i: Int): Pt = points[i]

    /** Distance that is not distorted by the non-square camera frame. */
    fun dist(a: Int, b: Int): Float =
        hypot((points[a].x - points[b].x) * aspect, points[a].y - points[b].y)
}

class HandTracker(context: Context) : Closeable {
    private val landmarker: HandLandmarker
    private var lastTimestamp = 0L

    init {
        // GPU is several times faster; some devices fail to init it, so fall back to CPU
        landmarker = try {
            create(context, Delegate.GPU)
        } catch (e: Exception) {
            Log.w("HandTracker", "GPU delegate unavailable, using CPU", e)
            create(context, Delegate.CPU)
        }
    }

    private fun create(context: Context, delegate: Delegate): HandLandmarker {
        val base = BaseOptions.builder()
            .setModelAssetPath("hand_landmarker.task")
            .setDelegate(delegate)
            .build()
        val options = HandLandmarker.HandLandmarkerOptions.builder()
            .setBaseOptions(base)
            .setRunningMode(RunningMode.VIDEO)
            .setNumHands(1)
            .setMinHandDetectionConfidence(0.5f)
            .setMinHandPresenceConfidence(0.5f)
            .setMinTrackingConfidence(0.5f)
            .build()
        return HandLandmarker.createFromOptions(context, options)
    }

    fun detect(bitmap: Bitmap): Hand? {
        // VIDEO mode requires strictly increasing timestamps
        var ts = SystemClock.uptimeMillis()
        if (ts <= lastTimestamp) ts = lastTimestamp + 1
        lastTimestamp = ts

        val result = landmarker.detectForVideo(BitmapImageBuilder(bitmap).build(), ts)
        val landmarks = result.landmarks().firstOrNull() ?: return null
        val aspect = bitmap.width.toFloat() / bitmap.height
        return Hand(landmarks.map { Hand.Pt(it.x(), it.y()) }, aspect)
    }

    override fun close() = landmarker.close()
}
