package io.github.pieterjanpintens.ascentcamera

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.ConnectivityManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.provider.Settings
import android.view.View
import android.widget.Button
import android.widget.ImageView
import android.widget.TextView
import java.net.HttpURLConnection
import java.net.Inet4Address
import java.net.URL
import kotlin.concurrent.thread

/** The setup screen: start/stop the camera service, show the address and pairing code, take a test photo. */
class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var address: TextView
    private lateinit var code: TextView
    private lateinit var startStop: Button
    private lateinit var testPhoto: Button
    private lateinit var battery: Button
    private lateinit var testResult: TextView
    private lateinit var preview: ImageView

    private val handler = Handler(Looper.getMainLooper())
    private val refresher = object : Runnable {
        override fun run() {
            refresh()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        status = findViewById(R.id.status)
        address = findViewById(R.id.address)
        code = findViewById(R.id.code)
        startStop = findViewById(R.id.start_stop)
        testPhoto = findViewById(R.id.test_photo)
        battery = findViewById(R.id.battery)
        testResult = findViewById(R.id.test_result)
        preview = findViewById(R.id.preview)

        startStop.setOnClickListener {
            if (CameraService.running) stopService(Intent(this, CameraService::class.java)) else startCamera()
        }
        testPhoto.setOnClickListener { takeTestPhoto() }
        battery.setOnClickListener { askForBatteryExemption() }
        findViewById<Button>(R.id.new_code).setOnClickListener {
            Pairing.newCode(this)
            refresh()
        }
    }

    override fun onResume() {
        super.onResume()
        handler.post(refresher)
    }

    override fun onPause() {
        handler.removeCallbacks(refresher)
        super.onPause()
    }

    private fun refresh() {
        val running = CameraService.running
        status.text = if (running) {
            "Camera ready. You can lock the phone now.\n" + CameraService.lastEvent
        } else {
            "Stopped." + if (CameraService.lastEvent.isNotEmpty()) "\n" + CameraService.lastEvent else ""
        }
        address.text = wifiAddress()?.let { "$it:${CameraService.PORT}" } ?: "Not connected to a network"
        code.text = Pairing.code(this)
        startStop.text = if (running) "Stop the camera" else "Start the camera"
        testPhoto.isEnabled = running
        val power = getSystemService(PowerManager::class.java)
        battery.visibility = if (power.isIgnoringBatteryOptimizations(packageName)) View.GONE else View.VISIBLE
    }

    private fun startCamera() {
        val needed = mutableListOf(Manifest.permission.CAMERA)
        if (Build.VERSION.SDK_INT >= 33) needed.add(Manifest.permission.POST_NOTIFICATIONS)
        val missing = needed.filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            requestPermissions(missing.toTypedArray(), 1)
            return
        }
        startForegroundService(Intent(this, CameraService::class.java))
        handler.postDelayed({ refresh() }, 300)
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (checkSelfPermission(Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED) {
            startForegroundService(Intent(this, CameraService::class.java))
        } else {
            testResult.text = "The app needs the camera permission to take photos."
        }
    }

    @SuppressLint("BatteryLife")
    private fun askForBatteryExemption() {
        startActivity(Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName")))
    }

    /** The phone's IPv4 address on the current network, or null. */
    private fun wifiAddress(): String? {
        val connectivity = getSystemService(ConnectivityManager::class.java)
        val properties = connectivity.getLinkProperties(connectivity.activeNetwork) ?: return null
        return properties.linkAddresses.map { it.address }
            .firstOrNull { it is Inet4Address && !it.isLoopbackAddress }?.hostAddress
    }

    /** Asks our own server for a photo, exactly like Ascent will, and shows a small copy of it. */
    private fun takeTestPhoto() {
        testResult.text = "Taking a photo..."
        testPhoto.isEnabled = false
        val key = Pairing.code(this)
        thread {
            var message: String
            var bitmap: Bitmap? = null
            try {
                val started = System.currentTimeMillis()
                val connection = URL("http://127.0.0.1:${CameraService.PORT}/photo").openConnection() as HttpURLConnection
                connection.setRequestProperty("X-Key", key)
                connection.connectTimeout = 5_000
                connection.readTimeout = 30_000
                if (connection.responseCode == 200) {
                    val bytes = connection.inputStream.use { it.readBytes() }
                    val rotation = connection.getHeaderField("X-Rotation")?.toIntOrNull() ?: 0
                    val seconds = (System.currentTimeMillis() - started) / 1000.0
                    val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                    BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
                    val small = BitmapFactory.decodeByteArray(bytes, 0, bytes.size, BitmapFactory.Options().apply { inSampleSize = 8 })
                    bitmap = small?.let { Bitmap.createBitmap(it, 0, 0, it.width, it.height, Matrix().apply { postRotate(rotation.toFloat()) }, true) }
                    message = "Photo: ${bounds.outWidth} x ${bounds.outHeight}, ${bytes.size / 1024} KB, turned $rotation°, ${"%.1f".format(seconds)} s"
                } else {
                    val error = connection.errorStream?.use { String(it.readBytes()) } ?: ""
                    message = "Failed (${connection.responseCode}): $error"
                }
            } catch (e: Exception) {
                message = "Failed: ${e.message}"
            }
            runOnUiThread {
                testResult.text = message
                if (bitmap != null) preview.setImageBitmap(bitmap)
                testPhoto.isEnabled = CameraService.running
            }
        }
    }
}
