package com.gesturecontrol.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private lateinit var status: TextView
    private lateinit var lastGesture: TextView
    private lateinit var btnCamera: Button
    private lateinit var btnAccessibility: Button
    private lateinit var btnToggle: Button

    private val permissionLauncher =
        registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) { refresh() }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        lastGesture = findViewById(R.id.lastGesture)
        btnCamera = findViewById(R.id.btnCamera)
        btnAccessibility = findViewById(R.id.btnAccessibility)
        btnToggle = findViewById(R.id.btnToggle)

        btnCamera.setOnClickListener { requestPermissions() }
        btnAccessibility.setOnClickListener {
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Toast.makeText(this, "Найдите «Gesture Control» и включите службу", Toast.LENGTH_LONG).show()
        }
        btnToggle.setOnClickListener { toggle() }
        findViewById<Button>(R.id.btnPreview).setOnClickListener {
            if (hasCamera()) startActivity(Intent(this, PreviewActivity::class.java)) else requestPermissions()
        }

        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                launch {
                    combine(AppState.cameraRunning, AppState.accessibilityEnabled) { _, _ -> }
                        .collect { refresh() }
                }
                launch { AppState.lastGesture.collect { lastGesture.text = "Последний жест: $it" } }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
    }

    private fun hasCamera() =
        ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    private fun requestPermissions() {
        val permissions = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions += Manifest.permission.POST_NOTIFICATIONS
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun toggle() {
        val intent = Intent(this, CameraService::class.java)
        if (AppState.cameraRunning.value) {
            stopService(intent)
            return
        }
        if (!hasCamera()) {
            requestPermissions()
            return
        }
        if (GestureAccessibilityService.instance == null) {
            Toast.makeText(this, "Сначала включите службу специальных возможностей", Toast.LENGTH_LONG).show()
            return
        }
        // Android 14 only allows a camera foreground service to start while the app is visible
        ContextCompat.startForegroundService(this, intent)
    }

    private fun refresh() {
        val camera = hasCamera()
        val accessibility = GestureAccessibilityService.instance != null
        val running = AppState.cameraRunning.value

        btnCamera.isEnabled = !camera
        btnAccessibility.isEnabled = !accessibility
        btnToggle.isEnabled = camera && accessibility
        btnToggle.setText(if (running) R.string.btn_stop else R.string.btn_start)

        status.text = buildString {
            appendLine("Камера: " + if (camera) "✅ разрешена" else "❌ нет доступа")
            appendLine("Спец. возможности: " + if (accessibility) "✅ включены" else "❌ выключены")
            append("Управление: " + if (running) "🟢 работает" else "⚪ остановлено")
        }
    }
}
