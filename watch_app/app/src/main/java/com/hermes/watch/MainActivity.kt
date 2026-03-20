package com.agentpet.watch

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.speech.RecognizerIntent
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.wear.compose.material.Text
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.util.concurrent.TimeUnit
import kotlin.math.absoluteValue

enum class AppScreen { INTRO, WIZARD_URL, WIZARD_KEY, READY }

data class PetReaction(
    val emotion: String,
    val chipText: String? = null,
    val fullText: String? = null,
    val durationMs: Long = 2200,
    val vibrateMs: Long = 0,
)

private fun prefersVerboseReply(spokenText: String): Boolean {
    val prompt = spokenText.lowercase()
    val verboseTriggers = listOf(
        "dime", "decime", "decir", "explica", "explicame", "contame",
        "cuentame", "resume", "detalle", "detalles", "que paso",
        "que dice", "como", "por que", "show", "mostrar", "mostrame",
        "lee", "leer"
    )
    return verboseTriggers.any { prompt.contains(it) }
}

private fun isSimpleTaskRequest(spokenText: String): Boolean {
    val prompt = spokenText.lowercase()
    val quickTaskTriggers = listOf(
        "recorda", "recuerda", "anota", "agenda", "pon", "pone", "crea",
        "manda", "envia", "abre", "abrime", "abrir", "llama", "silencia",
        "activa", "desactiva", "marca", "busca", "tarea", "todo", "mensaje"
    )
    return quickTaskTriggers.any { prompt.contains(it) }
}

private fun buildSuccessReaction(
    spokenText: String,
    backendText: String,
    fallbackEmotion: String
): PetReaction {
    val wantsVerbose = prefersVerboseReply(spokenText)
    val simpleTask = isSimpleTaskRequest(spokenText)
    val cleanText = backendText.trim()

    if (wantsVerbose) {
        return PetReaction(
            emotion = fallbackEmotion,
            chipText = "Te cuento",
            fullText = cleanText.ifEmpty { "Listo." },
            durationMs = 9000
        )
    }

    if (simpleTask) {
        val chip = when {
            cleanText.contains("error", ignoreCase = true) -> "No pude"
            cleanText.contains("record", ignoreCase = true) -> "Agendado"
            cleanText.contains("mensaje", ignoreCase = true) -> "Enviado"
            cleanText.contains("abr", ignoreCase = true) -> "Abierto"
            else -> "Hecho"
        }
        return PetReaction(
            emotion = if (fallbackEmotion == "0_0") "^_^" else fallbackEmotion,
            chipText = chip,
            durationMs = 1800
        )
    }

    if (cleanText.length <= 32) {
        return PetReaction(
            emotion = fallbackEmotion,
            chipText = cleanText.ifEmpty { "Listo" },
            durationMs = 2600
        )
    }

    return PetReaction(
        emotion = fallbackEmotion,
        chipText = "Listo",
        fullText = cleanText,
        durationMs = 7000
    )
}

class MainActivity : ComponentActivity() {

    companion object {
        val moodClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(8, TimeUnit.SECONDS)
            .readTimeout(8, TimeUnit.SECONDS)
            .build()

        val chatClient: OkHttpClient = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(90, TimeUnit.SECONDS)
            .writeTimeout(15, TimeUnit.SECONDS)
            .build()
    }

    private val permissionsLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {
        setupBackgroundServices()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val permissions = mutableListOf(
            Manifest.permission.BODY_SENSORS,
            Manifest.permission.ACTIVITY_RECOGNITION,
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionsLauncher.launch(permissions.toTypedArray())

        setContent { AgentPetApp() }
    }

    private fun setupBackgroundServices() {
        val wm = WorkManager.getInstance(this)
        wm.enqueue(OneTimeWorkRequestBuilder<HealthSyncWorker>().build())
        wm.enqueueUniquePeriodicWork(
            "health_sync",
            ExistingPeriodicWorkPolicy.KEEP,
            PeriodicWorkRequestBuilder<HealthSyncWorker>(15, TimeUnit.MINUTES).build()
        )
        startForegroundService(Intent(this, AgentPetNotificationService::class.java))
    }

    @Composable
    fun AgentPetApp() {
        val prefs = getSharedPreferences("agentpet_prefs", Context.MODE_PRIVATE)
        val savedUrl = prefs.getString("vps_url", "") ?: ""
        val savedKey = prefs.getString("api_key", "") ?: ""
        val isConfigured = savedUrl.isNotEmpty() && !savedUrl.contains("YOUR_IP") && savedKey.isNotEmpty()

        var screen by remember { mutableStateOf(AppScreen.INTRO) }

        when (screen) {
            AppScreen.INTRO -> IntroScreen(isConfigured) {
                screen = if (isConfigured) AppScreen.READY else AppScreen.WIZARD_URL
            }
            AppScreen.WIZARD_URL -> WizardUrlScreen { url ->
                prefs.edit().putString("vps_url", url).apply()
                screen = AppScreen.WIZARD_KEY
            }
            AppScreen.WIZARD_KEY -> WizardKeyScreen { key ->
                prefs.edit().putString("api_key", key).apply()
                screen = AppScreen.READY
            }
            AppScreen.READY -> ReadyScreen(onShowSettings = { screen = AppScreen.WIZARD_URL })
        }
    }

    @Composable
    fun IntroScreen(isConfigured: Boolean, onDone: () -> Unit) {
        var face by remember { mutableStateOf("-_-") }

        LaunchedEffect(Unit) {
            if (isConfigured) {
                face = "0_0"; delay(200)
                face = "-_-"; delay(80)
                face = "^_^"; delay(300)
            } else {
                delay(300)
                face = "-_-"; delay(120)
                face = "0_0"; delay(400)
                face = "-_-"; delay(100)
                face = "0_0"; delay(400)
                face = "-_-"; delay(100)
                face = "0_0"; delay(500)
                face = "^_^"; delay(600)
            }
            onDone()
        }

        Box(Modifier.fillMaxSize().background(Color.Black), contentAlignment = Alignment.Center) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                FaceCanvas(
                    emotion = face,
                    modifier = Modifier.fillMaxWidth().height(90.dp)
                )
                Spacer(Modifier.height(8.dp))
                Text(
                    if (isConfigured) "Despertando..." else "Iniciando...",
                    color = Color(0xFF44FF44),
                    fontSize = 12.sp
                )
            }
        }
    }

    @Composable
    fun WizardUrlScreen(onUrlSet: (String) -> Unit) {
        var inputText by remember { mutableStateOf("") }
        var face by remember { mutableStateOf("0_?") }

        LaunchedEffect(Unit) {
            val rng = java.util.Random()
            while (true) {
                delay(2000L + rng.nextInt(2000))
                face = "-_-"
                delay(120)
                face = "0_?"
            }
        }

        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.get(0) ?: ""
                if (spoken.isNotEmpty()) {
                    var cleaned = spoken.trim()
                        .replace(" punto ", ".")
                        .replace(" punto", ".")
                        .replace("punto ", ".")
                        .replace(" dos puntos ", ":")
                        .replace("dos puntos", ":")
                        .replace(" ", "")
                    if (!cleaned.startsWith("http")) cleaned = "http://$cleaned"
                    inputText = cleaned
                    face = "^_^"
                }
            }
        }

        val launchVoice = {
            launcher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Dime la IP y puerto...")
            })
        }

        Box(
            Modifier.fillMaxSize().background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { if (inputText.isEmpty()) launchVoice() },
                        onLongPress = { if (inputText.isNotEmpty()) onUrlSet(inputText) }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 12.dp)
            ) {
                FaceCanvas(emotion = face, modifier = Modifier.fillMaxWidth().height(72.dp))
                Spacer(Modifier.height(4.dp))
                Text("A que IP\nnos conectamos?", color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                if (inputText.isEmpty()) {
                    Text("(ej: http://1.2.3.4:8000)", color = Color.Gray, fontSize = 9.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(10.dp))
                    Text("[ PULSE PARA HABLAR ]", color = Color(0xFF00FF41), fontSize = 11.sp)
                } else {
                    Text(inputText, color = Color(0xFF44FF44), fontSize = 10.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(8.dp))
                    Text("[ TAP ] cambiar", color = Color.Gray, fontSize = 9.sp)
                    Text("[ HOLD ] confirmar", color = Color(0xFF00FF41), fontSize = 9.sp)
                }
            }
        }
    }

    @Composable
    fun WizardKeyScreen(onKeySet: (String) -> Unit) {
        var inputText by remember { mutableStateOf("") }
        var face by remember { mutableStateOf("0_?") }

        LaunchedEffect(Unit) {
            val rng = java.util.Random()
            while (true) {
                delay(2000L + rng.nextInt(2000))
                face = "-_-"
                delay(120)
                face = "0_?"
            }
        }

        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val spoken = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.get(0) ?: ""
                if (spoken.isNotEmpty()) {
                    inputText = spoken.trim().replace(" ", "_")
                    face = "^_^"
                }
            }
        }

        val launchVoice = {
            launcher.launch(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Dime la contrasena...")
            })
        }

        Box(
            Modifier.fillMaxSize().background(Color.Black)
                .pointerInput(Unit) {
                    detectTapGestures(
                        onTap = { if (inputText.isEmpty()) launchVoice() },
                        onLongPress = { if (inputText.isNotEmpty()) onKeySet(inputText) }
                    )
                },
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center,
                modifier = Modifier.padding(horizontal = 12.dp)
            ) {
                FaceCanvas(emotion = face, modifier = Modifier.fillMaxWidth().height(72.dp))
                Spacer(Modifier.height(4.dp))
                Text("Cual sera\ntu contrasena?", color = Color.White, fontSize = 13.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                if (inputText.isEmpty()) {
                    Text("(di la clave de tu bridge)", color = Color.Gray, fontSize = 9.sp, textAlign = TextAlign.Center)
                    Spacer(Modifier.height(10.dp))
                    Text("[ PULSE PARA HABLAR ]", color = Color(0xFF00FF41), fontSize = 11.sp)
                } else {
                    val obscured = "*".repeat(inputText.length.coerceAtMost(12))
                    Text(obscured, color = Color(0xFF44FF44), fontSize = 14.sp)
                    Spacer(Modifier.height(8.dp))
                    Text("[ TAP ] cambiar", color = Color.Gray, fontSize = 9.sp)
                    Text("[ HOLD ] confirmar", color = Color(0xFF00FF41), fontSize = 9.sp)
                }
            }
        }
    }

    @Composable
    fun ReadyScreen(onShowSettings: () -> Unit) {
        val prefs = getSharedPreferences("agentpet_prefs", Context.MODE_PRIVATE)
        val sensors = rememberWatchSensors()

        val vpsUrl by remember { mutableStateOf(prefs.getString("vps_url", "") ?: "") }
        val apiKey by remember { mutableStateOf(prefs.getString("api_key", "") ?: "") }

        var baseEmoji by remember { mutableStateOf("0_0") }
        var transientReaction by remember { mutableStateOf<PetReaction?>(null) }
        var longReply by remember { mutableStateOf<String?>(null) }
        var isThinking by remember { mutableStateOf(false) }
        val textScrollState = rememberScrollState()

        val sensorEmoji = when {
            sensors.isShaking -> "O_O"
            sensors.isCharging -> "^_^"
            sensors.batteryPct <= 5 -> "u_u"
            sensors.batteryPct <= 20 -> "-_-"
            else -> null
        }
        val activeFace = transientReaction?.emotion ?: if (isThinking) "o_O" else (sensorEmoji ?: baseEmoji)

        LaunchedEffect(vpsUrl) {
            if (vpsUrl.isEmpty()) return@LaunchedEffect
            while (true) {
                try {
                    val (newEmoji, notif) = withContext(Dispatchers.IO) {
                        val response = moodClient.newCall(
                            Request.Builder()
                                .url("$vpsUrl/mood")
                                .header("X-API-Key", apiKey)
                                .build()
                        ).execute()
                        if (response.isSuccessful) {
                            val json = JSONObject(response.body?.string() ?: "{}")
                            Pair(json.optString("emoji", "0_0"), json.optString("notification", ""))
                        } else {
                            Pair(baseEmoji, "")
                        }
                    }
                    baseEmoji = newEmoji
                    if (notif.isNotEmpty()) {
                        transientReaction = PetReaction(
                            emotion = newEmoji,
                            chipText = notif.take(22),
                            durationMs = 2200,
                            vibrateMs = 250
                        )
                    }
                } catch (_: Exception) {
                    baseEmoji = "x_X"
                }
                delay(10_000)
            }
        }

        LaunchedEffect(baseEmoji, isThinking, transientReaction) {
            val random = java.util.Random()
            while (true) {
                delay(2000L + random.nextInt(3000))
                if (transientReaction == null && !isThinking && (baseEmoji == "0_0" || baseEmoji == "o_O")) {
                    when (random.nextInt(5)) {
                        0 -> transientReaction = PetReaction("-_-", durationMs = 350)
                        1 -> transientReaction = PetReaction("0_<", durationMs = 600)
                        2 -> transientReaction = PetReaction(">_0", durationMs = 600)
                        3 -> transientReaction = PetReaction("._.", durationMs = 1400)
                        4 -> transientReaction = PetReaction("^_^", durationMs = 550)
                    }
                }
            }
        }

        LaunchedEffect(transientReaction) {
            val reaction = transientReaction ?: return@LaunchedEffect
            if (reaction.vibrateMs > 0) vibrate(reaction.vibrateMs)
            delay(reaction.durationMs)
            if (transientReaction == reaction) {
                transientReaction = null
            }
        }

        LaunchedEffect(longReply) {
            if (!longReply.isNullOrBlank()) {
                textScrollState.animateScrollTo(0)
                delay(6500)
                if (!isThinking && !longReply.isNullOrBlank()) {
                    longReply = null
                }
            }
        }

        LaunchedEffect(sensors.isShaking) {
            if (sensors.isShaking) {
                transientReaction = PetReaction(
                    emotion = "O_O",
                    chipText = "Epa",
                    durationMs = 900,
                    vibrateMs = 120
                )
            }
        }

        LaunchedEffect(sensors.isCharging) {
            if (sensors.isCharging) {
                transientReaction = PetReaction(
                    emotion = "^_^",
                    chipText = "Cargando",
                    durationMs = 1800
                )
            }
        }

        LaunchedEffect(sensors.batteryPct, sensors.isCharging) {
            when {
                sensors.isCharging -> Unit
                sensors.batteryPct in 0..5 -> transientReaction = PetReaction("u_u", "Sin energia", durationMs = 2200)
                sensors.batteryPct in 6..20 -> transientReaction = PetReaction("-_-", "Bateria baja", durationMs = 2200)
            }
        }

        val scope = rememberCoroutineScope()
        val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            if (result.resultCode == Activity.RESULT_OK) {
                val spokenText = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.get(0) ?: ""
                if (spokenText.isNotEmpty()) {
                    longReply = null
                    isThinking = true
                    transientReaction = PetReaction(
                        emotion = if (spokenText.length > 24) "0_?" else "o_O",
                        chipText = if (isSimpleTaskRequest(spokenText)) "Voy" else "Escuchando",
                        durationMs = 1200
                    )
                    scope.launch {
                        try {
                            val json = JSONObject().apply { put("message", spokenText) }
                            val body = withContext(Dispatchers.IO) {
                                val response = chatClient.newCall(
                                    Request.Builder()
                                        .url("$vpsUrl/chat")
                                        .header("X-API-Key", apiKey)
                                        .post(json.toString().toRequestBody("application/json".toMediaType()))
                                        .build()
                                ).execute()
                                if (response.isSuccessful) response.body?.string() else null
                            }
                            if (body != null) {
                                val resp = JSONObject(body)
                                val responseText = resp.optString("response", "Hecho.")
                                val newEmoji = resp.optString("emoji", "0_0")
                                baseEmoji = newEmoji
                                val reaction = buildSuccessReaction(spokenText, responseText, newEmoji)
                                transientReaction = reaction
                                longReply = reaction.fullText
                                val vibMs = resp.optInt("vibrate", 0)
                                if (vibMs > 0) {
                                    transientReaction = reaction.copy(vibrateMs = vibMs.toLong())
                                }
                            } else {
                                val errorText = "La API rechazo la accion."
                                transientReaction = PetReaction(
                                    emotion = "x_X",
                                    chipText = "No pude",
                                    fullText = errorText,
                                    durationMs = 5000,
                                    vibrateMs = 220
                                )
                                longReply = errorText
                            }
                        } catch (e: Exception) {
                            val errorText = e.message?.take(50) ?: "Error de conexion."
                            transientReaction = PetReaction(
                                emotion = "x_X",
                                chipText = "Error",
                                fullText = errorText,
                                durationMs = 5000,
                                vibrateMs = 220
                            )
                            longReply = errorText
                        } finally {
                            isThinking = false
                        }
                    }
                }
            }
        }

        Box(
            modifier = Modifier.fillMaxSize().background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        detectTapGestures(
                            onTap = {
                                val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                                    putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                                    putExtra(RecognizerIntent.EXTRA_PROMPT, "Habla...")
                                }
                                try {
                                    transientReaction = PetReaction("0_?", "Te escucho", durationMs = 1000)
                                    launcher.launch(intent)
                                } catch (_: Exception) {
                                    val errorText = "No pude abrir el reconocimiento de voz."
                                    transientReaction = PetReaction(
                                        emotion = "x_X",
                                        chipText = "Sin micro",
                                        fullText = errorText,
                                        durationMs = 5000
                                    )
                                    longReply = errorText
                                }
                            },
                            onLongPress = { onShowSettings() }
                        )
                    }
            ) {
                FaceCanvas(
                    emotion = activeFace,
                    sensorGaze = sensors.gazeOffset,
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 10.dp, vertical = 6.dp)
                )

                Column(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    val chip = if (isThinking) "Pensando..." else transientReaction?.chipText
                    if (chip != null) {
                        Text(
                            text = chip,
                            color = Color(0xFF00FF41),
                            fontSize = 10.sp,
                            textAlign = TextAlign.Center,
                            modifier = Modifier
                                .clip(RoundedCornerShape(999.dp))
                                .background(Color(0x2218FF6D))
                                .padding(horizontal = 10.dp, vertical = 4.dp)
                        )
                        Spacer(Modifier.height(6.dp))
                    }

                    if (!longReply.isNullOrBlank()) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .heightIn(max = 72.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0x22111111))
                                .verticalScroll(textScrollState)
                                .padding(horizontal = 10.dp, vertical = 8.dp)
                        ) {
                            Text(
                                text = longReply.orEmpty(),
                                color = Color.LightGray,
                                fontSize = 10.sp,
                                textAlign = TextAlign.Center,
                                lineHeight = 13.sp,
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    } else {
                        val footer = when {
                            sensors.isCharging -> "Long press: ajustes"
                            sensors.batteryPct <= 20 -> "Bateria ${sensors.batteryPct}%"
                            sensors.gazeOffset.x.absoluteValue > 0.18f || sensors.gazeOffset.y.absoluteValue > 0.18f -> "Te sigo"
                            else -> "Tap: habla"
                        }
                        Text(
                            text = footer,
                            color = Color(0xFF6A6A6A),
                            fontSize = 8.sp,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }
        }
    }

    fun vibrate(duration: Long) {
        val vibrator = getSystemService(Vibrator::class.java)
        vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
    }
}
