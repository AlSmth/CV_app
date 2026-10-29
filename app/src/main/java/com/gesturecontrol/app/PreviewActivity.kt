package com.gesturecontrol.app

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.core.content.ContextCompat

/** Shows what the camera sees: the frame, the detected hand skeleton and the recognized pose. */
class PreviewActivity : ComponentActivity() {

    private lateinit var view: HandPreviewView
    private var startedService = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        title = getString(R.string.preview_title)
        view = HandPreviewView(this)
        setContentView(view)
    }

    override fun onStart() {
        super.onStart()
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
            Toast.makeText(this, "Сначала разрешите камеру", Toast.LENGTH_LONG).show()
            finish()
            return
        }
        AppState.previewListener = { frame -> view.post { view.show(frame) } }
        if (!AppState.cameraRunning.value) {
            ContextCompat.startForegroundService(this, Intent(this, CameraService::class.java))
            startedService = true
        }
    }

    override fun onStop() {
        AppState.previewListener = null
        // Only stop the camera if gesture control was not running before the preview
        if (startedService) {
            stopService(Intent(this, CameraService::class.java))
            startedService = false
        }
        super.onStop()
    }

    private class HandPreviewView(context: Context) : View(context) {
        private val density = resources.displayMetrics.density
        private val bone = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.rgb(0, 230, 118)
            strokeWidth = 4f * density
            strokeCap = Paint.Cap.ROUND
        }
        private val joint = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.rgb(255, 87, 34) }
        private val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = Color.WHITE
            textSize = 22f * density
            setShadowLayer(4f * density, 0f, 0f, Color.BLACK)
        }
        private val small = Paint(text).apply { textSize = 15f * density }
        private val matrix = Matrix()
        private var frame: PreviewFrame? = null

        init {
            setBackgroundColor(Color.BLACK)
        }

        fun show(frame: PreviewFrame) {
            this.frame = frame
            invalidate()
        }

        override fun onDraw(canvas: Canvas) {
            val f = frame
            if (f == null) {
                canvas.drawText("Запуск камеры…", 16 * density, 40 * density, text)
                return
            }
            val bmp = f.bitmap
            val scale = minOf(width / bmp.width.toFloat(), height / bmp.height.toFloat())
            val left = (width - bmp.width * scale) / 2
            val top = (height - bmp.height * scale) / 2
            matrix.setScale(scale, scale)
            matrix.postTranslate(left, top)
            canvas.drawBitmap(bmp, matrix, null)

            f.hand?.let { hand ->
                fun px(i: Int) = left + hand[i].x * bmp.width * scale
                fun py(i: Int) = top + hand[i].y * bmp.height * scale
                for ((a, b) in CONNECTIONS) canvas.drawLine(px(a), py(a), px(b), py(b), bone)
                for (i in hand.points.indices) canvas.drawCircle(px(i), py(i), 5f * density, joint)
            }

            var y = top + 36 * density
            canvas.drawText(f.pose, 16 * density, y, text)
            y += 32 * density
            canvas.drawText("Последний жест: ${AppState.lastGesture.value}", 16 * density, y, small)
            y += 24 * density
            canvas.drawText("%.0f кадров/с".format(f.fps), 16 * density, y, small)
            canvas.drawText(
                "Здесь жесты не выполняются",
                16 * density, height - 24 * density, small,
            )
        }

        private companion object {
            val CONNECTIONS = listOf(
                0 to 1, 1 to 2, 2 to 3, 3 to 4,
                0 to 5, 5 to 6, 6 to 7, 7 to 8,
                5 to 9, 9 to 10, 10 to 11, 11 to 12,
                9 to 13, 13 to 14, 14 to 15, 15 to 16,
                13 to 17, 17 to 18, 18 to 19, 19 to 20, 0 to 17,
            )
        }
    }
}
