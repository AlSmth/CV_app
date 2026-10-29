package com.gesturecontrol.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.view.Display
import android.graphics.Bitmap
import android.graphics.Matrix
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.Surface
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleService
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Foreground service that keeps the front camera running while other apps are open,
 * detects the hand and forwards recognized gestures to [GestureAccessibilityService].
 */
class CameraService : LifecycleService() {

    companion object {
        const val ACTION_STOP = "com.gesturecontrol.app.STOP"
        private const val CHANNEL_ID = "gesture_control"
        private const val NOTIFICATION_ID = 1
        private const val TAG = "CameraService"
    }

    private lateinit var executor: ExecutorService
    private val mainHandler = Handler(Looper.getMainLooper())
    private val recognizer = GestureRecognizer()
    private var tracker: HandTracker? = null
    private var cameraProvider: ProcessCameraProvider? = null
    private var analysis: ImageAnalysis? = null
    private var lastFrameTime = 0L
    private var fps = 0f

    // The camera is useless with the screen off, so pause it to save battery
    private val screenReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            when (intent.action) {
                Intent.ACTION_SCREEN_OFF -> cameraProvider?.unbindAll()
                Intent.ACTION_SCREEN_ON -> bindCamera()
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        startInForeground()
        executor = Executors.newSingleThreadExecutor()
        // Runs before the first frame because the analyzer uses the same single thread
        executor.execute {
            try {
                tracker = HandTracker(this)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load the hand model", e)
                mainHandler.post { stopSelf() }
            }
        }
        ContextCompat.registerReceiver(
            this,
            screenReceiver,
            IntentFilter().apply {
                addAction(Intent.ACTION_SCREEN_OFF)
                addAction(Intent.ACTION_SCREEN_ON)
            },
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
        startCamera()
        AppState.cameraRunning.value = true
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        super.onStartCommand(intent, flags, startId)
        if (intent?.action == ACTION_STOP) stopSelf()
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        AppState.cameraRunning.value = false
        unregisterReceiver(screenReceiver)
        cameraProvider?.unbindAll()
        executor.execute { tracker?.close() }
        executor.shutdown()
        GestureAccessibilityService.instance?.hideCursor()
        super.onDestroy()
    }

    private fun startCamera() {
        val future = ProcessCameraProvider.getInstance(this)
        future.addListener({
            cameraProvider = future.get()
            analysis = ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            )
                        )
                        .build()
                )
                .setTargetRotation(displayRotation())
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_RGBA_8888)
                .build()
                .also { it.setAnalyzer(executor, ::analyze) }
            bindCamera()
        }, ContextCompat.getMainExecutor(this))
    }

    // Frames must be upright for the current screen orientation (tablets are often held in landscape)
    override fun onConfigurationChanged(newConfig: Configuration) {
        super.onConfigurationChanged(newConfig)
        analysis?.targetRotation = displayRotation()
    }

    private fun displayRotation(): Int =
        getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY)?.rotation
            ?: Surface.ROTATION_0

    private fun bindCamera() {
        val provider = cameraProvider ?: return
        val useCase = analysis ?: return
        try {
            provider.unbindAll()
            provider.bindToLifecycle(this, CameraSelector.DEFAULT_FRONT_CAMERA, useCase)
        } catch (e: Exception) {
            Log.e(TAG, "Camera bind failed", e)
            stopSelf()
        }
    }

    private fun analyze(image: ImageProxy) {
        try {
            val tracker = tracker ?: return
            val upright = uprightMirrored(image)
            val hand = tracker.detect(upright)
            val now = SystemClock.uptimeMillis()
            val events = recognizer.process(hand, now)

            if (lastFrameTime > 0) fps += (1000f / (now - lastFrameTime).coerceAtLeast(1) - fps) * 0.1f
            lastFrameTime = now
            val preview = AppState.previewListener
            preview?.invoke(PreviewFrame(upright, hand, recognizer.lastPoseLabel, fps))

            if (events.isEmpty()) return
            mainHandler.post {
                val service = GestureAccessibilityService.instance
                if (preview != null) service?.hideCursor()
                for (event in events) {
                    if (preview == null) service?.handle(event)
                    AppState.describe(event)?.let { AppState.lastGesture.value = it }
                }
            }
        } catch (e: Exception) {
            Log.w(TAG, "Frame skipped", e)
        } finally {
            image.close()
        }
    }

    /** Rotates the frame to portrait and mirrors it, so the hand moves like in a mirror. */
    private fun uprightMirrored(image: ImageProxy): Bitmap {
        val raw = image.toBitmap()
        val matrix = Matrix().apply {
            postRotate(image.imageInfo.rotationDegrees.toFloat())
            postScale(-1f, 1f)
        }
        return Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, matrix, false)
    }

    private fun startInForeground() {
        val manager = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            manager.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, getString(R.string.app_name), NotificationManager.IMPORTANCE_LOW)
            )
        }
        val stopIntent = PendingIntent.getService(
            this, 0,
            Intent(this, CameraService::class.java).setAction(ACTION_STOP),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val openIntent = PendingIntent.getActivity(
            this, 0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE,
        )
        val notification: Notification = NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_hand)
            .setContentTitle(getString(R.string.notif_title))
            .setContentText(getString(R.string.notif_text))
            .setContentIntent(openIntent)
            .addAction(0, getString(R.string.notif_stop), stopIntent)
            .setOngoing(true)
            .build()
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) ServiceInfo.FOREGROUND_SERVICE_TYPE_CAMERA else 0
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification, type)
    }
}
