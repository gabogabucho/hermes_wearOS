package com.agentpet.watch

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import kotlin.coroutines.resume

class HealthSyncWorker(appContext: Context, workerParams: WorkerParameters) :
    CoroutineWorker(appContext, workerParams) {

    private val client = OkHttpClient()
    private val sensorManager = appContext.getSystemService(SensorManager::class.java)

    override suspend fun doWork(): Result {
        val prefs = applicationContext.getSharedPreferences("agentpet_prefs", Context.MODE_PRIVATE)
        val baseUrl = prefs.getString("vps_url", "") ?: return Result.failure()
        if (baseUrl.isEmpty()) return Result.failure()
        val apiKey = prefs.getString("api_key", "") ?: return Result.failure()

        // Leer sensores directamente con timeout
        val heartRate = readSensor(Sensor.TYPE_HEART_RATE,   timeoutMs = 5000L)
        val steps     = readSensor(Sensor.TYPE_STEP_COUNTER, timeoutMs = 3000L)

        if (heartRate == 0 && steps == 0) return Result.success()

        // Cachear localmente para el tile y el proactive loop del bridge
        prefs.edit().putInt("last_hr", heartRate).putInt("last_steps", steps).apply()

        val json = JSONObject().apply {
            put("heart_rate", heartRate)
            put("steps", steps)
        }

        val request = Request.Builder()
            .url("$baseUrl/health")
            .addHeader("X-API-Key", apiKey)
            .post(json.toString().toRequestBody("application/json".toMediaType()))
            .build()

        return try {
            val response = client.newCall(request).execute()
            if (response.isSuccessful) {
                val mood = JSONObject(response.body?.string() ?: "{}").optString("mood", "0_0")
                prefs.edit().putString("current_emoji", mood).apply()
                Result.success()
            } else {
                Result.retry()
            }
        } catch (e: Exception) {
            Result.retry()
        }
    }

    // Lee un sensor y devuelve el primer valor > 0, o 0 si se agota el timeout
    private suspend fun readSensor(sensorType: Int, timeoutMs: Long): Int {
        val sensor = sensorManager.getDefaultSensor(sensorType) ?: return 0

        return withTimeoutOrNull(timeoutMs) {
            suspendCancellableCoroutine { cont ->
                val listener = object : SensorEventListener {
                    override fun onSensorChanged(event: SensorEvent) {
                        if (event.values.isNotEmpty() && event.values[0] > 0f) {
                            sensorManager.unregisterListener(this)
                            cont.resume(event.values[0].toInt())
                        }
                    }
                    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {}
                }
                sensorManager.registerListener(listener, sensor, SensorManager.SENSOR_DELAY_NORMAL)
                cont.invokeOnCancellation { sensorManager.unregisterListener(listener) }
            }
        } ?: 0
    }
}
