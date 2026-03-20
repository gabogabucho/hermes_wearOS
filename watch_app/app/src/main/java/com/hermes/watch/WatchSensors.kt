package com.agentpet.watch

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.*
import kotlin.math.sqrt

// ─────────────────────────────────────────────────────────────────────────────
//  WatchSensorsState — holds all reactive sensor data as Compose state
// ─────────────────────────────────────────────────────────────────────────────

class WatchSensorsState(private val context: Context) : SensorEventListener {

    private val sensorManager = context.getSystemService(SensorManager::class.java)
    private val scope = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())

    // ── Gravity-driven eye gaze (-1..1 per axis) ─────────────────────────────
    var gazeOffset by mutableStateOf(Offset.Zero)
        private set

    // ── Shake reaction (temporary) ───────────────────────────────────────────
    var isShaking by mutableStateOf(false)
        private set

    // ── Battery state ────────────────────────────────────────────────────────
    var batteryPct by mutableStateOf(100)
        private set
    var isCharging by mutableStateOf(false)
        private set

    // Low-pass gravity buffer (persistent across events)
    private val gravity = FloatArray(3) { 0f }
    private var lastShakeTime = 0L
    private var sampleCount = 0

    // ─────────────────────────────────────────────────────────────────────────
    //  Lifecycle
    // ─────────────────────────────────────────────────────────────────────────

    fun start() {
        val accel = sensorManager.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (accel != null) {
            // Callbacks on main looper → safe to write Compose state directly
            sensorManager.registerListener(
                this, accel,
                SensorManager.SENSOR_DELAY_UI,
                Handler(Looper.getMainLooper())
            )
        }

        // Battery: registerReceiver returns the current sticky broadcast immediately
        val batteryIntent = context.registerReceiver(
            batteryReceiver,
            IntentFilter(Intent.ACTION_BATTERY_CHANGED)
        )
        batteryIntent?.let { updateBattery(it) }
    }

    fun stop() {
        sensorManager.unregisterListener(this)
        try { context.unregisterReceiver(batteryReceiver) } catch (_: Exception) {}
        scope.cancel()
    }

    // ─────────────────────────────────────────────────────────────────────────
    //  SensorEventListener
    // ─────────────────────────────────────────────────────────────────────────

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        // Low-pass filter → isolate gravity component
        val alpha = 0.8f
        gravity[0] = alpha * gravity[0] + (1f - alpha) * x
        gravity[1] = alpha * gravity[1] + (1f - alpha) * y
        gravity[2] = alpha * gravity[2] + (1f - alpha) * z

        // High-pass → linear acceleration (no gravity)
        val lx = x - gravity[0]
        val ly = y - gravity[1]
        val lz = z - gravity[2]
        val shakeMag = sqrt(lx * lx + ly * ly + lz * lz)

        // Shake detection: > 14 m/s² linear acceleration, debounced 600ms
        val now = System.currentTimeMillis()
        if (shakeMag > 14f && (now - lastShakeTime) > 600L) {
            lastShakeTime = now
            triggerShake()
        }

        // Gravity gaze: update every 3rd sample (~5 Hz) to reduce recompositions
        if (++sampleCount % 3 == 0) {
            // gravity[0] (x): negative when tilted left → eyes look left (negate for natural feel)
            // gravity[1] (y): negative when held upright → compensate with small offset
            gazeOffset = Offset(
                x = (-gravity[0] / 9.8f * 0.55f).coerceIn(-0.85f, 0.85f),
                y = ( gravity[1] / 9.8f * 0.40f).coerceIn(-0.85f, 0.85f)
            )
        }
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}

    // ─────────────────────────────────────────────────────────────────────────
    //  Helpers
    // ─────────────────────────────────────────────────────────────────────────

    private fun triggerShake() {
        // Cancel any running shake coroutine and restart
        scope.launch {
            isShaking = true
            delay(1000)
            isShaking = false
        }
    }

    private fun updateBattery(intent: Intent) {
        val level  = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale  = intent.getIntExtra(BatteryManager.EXTRA_SCALE, 100)
        val status = intent.getIntExtra(BatteryManager.EXTRA_STATUS, -1)
        batteryPct  = if (scale > 0) (level * 100) / scale else 100
        isCharging  = status == BatteryManager.BATTERY_STATUS_CHARGING ||
                      status == BatteryManager.BATTERY_STATUS_FULL
    }

    private val batteryReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) = updateBattery(intent)
    }
}

// ─────────────────────────────────────────────────────────────────────────────
//  rememberWatchSensors — composable factory with automatic lifecycle binding
// ─────────────────────────────────────────────────────────────────────────────

@Composable
fun rememberWatchSensors(): WatchSensorsState {
    val context = LocalContext.current
    val state   = remember { WatchSensorsState(context) }
    DisposableEffect(Unit) {
        state.start()
        onDispose { state.stop() }
    }
    return state
}
