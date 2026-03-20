package com.agentpet.watch

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import androidx.core.app.NotificationCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import org.json.JSONObject

class AgentPetNotificationService : Service() {

    companion object {
        const val CHANNEL_BACKGROUND = "agentpet_bg"
        const val CHANNEL_PROACTIVE  = "agentpet_proactive"
        const val FOREGROUND_ID      = 101
        const val POLL_INTERVAL_MS   = 30_000L
    }

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val httpClient = OkHttpClient()

    override fun onCreate() {
        super.onCreate()
        createNotificationChannels()
        startForeground(FOREGROUND_ID, buildForegroundNotification())
        startPolling()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        super.onDestroy()
        scope.cancel()
    }

    private fun createNotificationChannels() {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_BACKGROUND, "AgentPet Background", NotificationManager.IMPORTANCE_LOW)
                .apply { description = "Servicio de escucha silencioso" }
        )
        nm.createNotificationChannel(
            NotificationChannel(CHANNEL_PROACTIVE, "AgentPet — Mensajes", NotificationManager.IMPORTANCE_HIGH)
                .apply { description = "Notificaciones proactivas y recordatorios"; enableVibration(true) }
        )
    }

    private fun buildForegroundNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE
        )
        return NotificationCompat.Builder(this, CHANNEL_BACKGROUND)
            .setSmallIcon(R.drawable.ic_hermes)
            .setContentTitle("0_0  AgentPet")
            .setContentText("Escuchando...")
            .setOngoing(true)
            .setContentIntent(openApp)
            .build()
    }

    private fun startPolling() {
        scope.launch {
            while (isActive) {
                checkForProactiveNotifications()
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    private fun checkForProactiveNotifications() {
        val prefs = getSharedPreferences("agentpet_prefs", Context.MODE_PRIVATE)
        val baseUrl = prefs.getString("vps_url", "") ?: return
        val apiKey  = prefs.getString("api_key",  "") ?: return
        if (baseUrl.isEmpty() || apiKey.isEmpty()) return

        try {
            val response = httpClient.newCall(
                Request.Builder().url("$baseUrl/mood").header("X-API-Key", apiKey).build()
            ).execute()

            if (response.isSuccessful) {
                val message = JSONObject(response.body?.string() ?: return).optString("notification", "")
                if (message.isNotEmpty()) showProactiveNotification(message)
            }
        } catch (_: Exception) { /* Fallo de red silencioso */ }
    }

    private fun showProactiveNotification(message: String) {
        val openApp = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        getSystemService(NotificationManager::class.java).notify(
            System.currentTimeMillis().toInt(),
            NotificationCompat.Builder(this, CHANNEL_PROACTIVE)
                .setSmallIcon(R.drawable.ic_hermes)
                .setContentTitle("AgentPet")
                .setContentText(message)
                .setAutoCancel(true)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setContentIntent(openApp)
                .build()
        )
    }
}
