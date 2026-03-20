package com.agentpet.watch

import android.content.Context
import androidx.health.services.client.PassiveListenerService
import androidx.health.services.client.data.DataPointContainer
import androidx.health.services.client.data.DataType

class AgentPetHealthService : PassiveListenerService() {

    override fun onNewDataPointsReceived(dataPoints: DataPointContainer) {
        val prefs = applicationContext.getSharedPreferences("agentpet_prefs", Context.MODE_PRIVATE)
        val editor = prefs.edit()

        // Frecuencia cardíaca — último sample
        dataPoints.getData(DataType.HEART_RATE_BPM).lastOrNull()?.let { point ->
            editor.putInt("last_hr", point.value.toInt())
        }

        // Pasos diarios acumulados
        dataPoints.getData(DataType.STEPS_DAILY).lastOrNull()?.let { point ->
            editor.putInt("last_steps", point.value.toInt())
        }

        editor.apply()
    }
}
