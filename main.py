import asyncio
import os
import re
import uuid
import time
import subprocess
from contextlib import asynccontextmanager

from fastapi import FastAPI, UploadFile, File, HTTPException, Depends, Security
from fastapi.security import APIKeyHeader
from pydantic import BaseModel
from typing import Optional

from transcription import engine

# ─────────────────────────────────────────────────────────────────────────────
#  Configuración del agente — apunta a cualquier CLI compatible
#
#  AGENT_CMD: comando base para invocar al agente. El prompt se pasa como
#             último argumento. Ejemplos:
#               hermes chat -Q -q       (Hermes Agent — default)
#               openai api chat.completions.create ...
#               llm -m gpt-4o
#  AGENT_API_KEY: clave para autenticar el reloj con este bridge
# ─────────────────────────────────────────────────────────────────────────────

AGENT_CMD = os.environ.get("AGENT_CMD", "hermes chat -Q -q").split()
API_KEY   = os.environ.get("AGENT_API_KEY", "agentpet_secreto_123")


def build_health_context() -> str:
    """Genera un bloque de contexto de salud para inyectar en cada prompt.
    Siempre presente — el agente no necesita llamar herramientas para saber el estado."""
    now = time.time()
    if state.last_update == 0:
        return "SALUD: sin datos aún (reloj no sincronizó). "
    age_min = round((now - state.last_update) / 60, 1)
    hr_note = f"{state.last_hr} BPM" if state.last_hr > 0 else "sin lectura"
    sed_note = f"{int(state.sedentary_mins)} min sin moverse" if state.sedentary_mins > 0 else "activo"
    return (
        f"SALUD ACTUAL (hace {age_min} min): "
        f"ritmo cardíaco = {hr_note}, "
        f"pasos hoy = {state.last_steps}, "
        f"{sed_note}. "
        "Respondé sobre salud directamente con estos datos, sin usar herramientas externas. "
    )


def clean_agent_output(text: str) -> str:
    """Elimina outputs de herramientas (JSON, curl, bloques de código) del stdout del agente.
    Conserva solo las líneas de texto natural destinadas al usuario."""
    import json as _json

    clean_lines = []
    in_json_block = False

    for line in text.split('\n'):
        stripped = line.strip()

        # Saltar líneas vacías al inicio
        if not stripped and not clean_lines:
            continue

        # Detectar inicio de bloque JSON multilínea
        if stripped.startswith(('{', '[')):
            # Intentar parsear en una sola línea
            try:
                _json.loads(stripped)
                continue  # JSON completo en una línea — saltar
            except _json.JSONDecodeError:
                in_json_block = True  # JSON multilínea — entrar en modo skip
                continue

        if in_json_block:
            if stripped.endswith(('}', ']')):
                in_json_block = False  # fin del bloque JSON
            continue

        # Saltar líneas que son pares clave:valor sueltos (restos de JSON)
        if re.match(r'^"?\w+"?\s*:\s*', stripped) and not re.search(r'[áéíóúñ¿¡]', stripped):
            continue

        # Saltar líneas de ejecución de herramientas de Hermes (┊ 💻 $ comando...)
        if stripped.startswith('┊') or stripped.startswith('|'):
            continue

        # Saltar líneas de session/tool del agente
        if re.match(r'(?i)^\[?\s*(session|tool_call|function_call|tool)\s*(id)?\s*:', stripped):
            continue

        # Saltar bloques de código markdown
        if stripped.startswith('```'):
            continue

        clean_lines.append(line)

    result = '\n'.join(clean_lines).strip()
    # Si después de limpiar no queda nada útil, devolver el texto original
    return result if len(result) > 3 else text.strip()


def ask_agent(text: str) -> str:
    health_ctx = build_health_context()
    full_prompt = (
        "INSTRUCCIÓN DEL SISTEMA: Estás respondiendo en la diminuta pantalla de mi Smartwatch. "
        "DEBES responder de forma enérgica y con MUCHA brevedad (máximo 1 o 2 líneas, sin viñetas, directo al punto). "
        f"{health_ctx}"
        "IMPORTANTE: Tienes una Cara Virtual en el reloj. Si quieres cambiar tu expresión facial, APUNTA literalmente uno de estos emojis al final de tu mensaje: "
        "^_^ (alegría), u_u (tristeza), >_< (enojo), O_O (sorpresa), ♥_♥ (amor), -_- (duda/aburrimiento). "
        "CRÍTICO: Respondé SOLO con el texto final para el usuario. "
        "NUNCA uses herramientas externas, curl ni skills para responder sobre salud — los datos ya están arriba. "
        "Sin JSON, sin pasos intermedios, sin outputs de comandos. "
        f"El usuario dice: {text}"
    )
    try:
        result = subprocess.run(
            AGENT_CMD + [full_prompt],
            capture_output=True,
            text=True,
            check=True,
            stdin=subprocess.DEVNULL,   # evitar que espere input interactivo
            timeout=25                  # matar si tarda más de 25 segundos
        )
        output = result.stdout.strip()
        # Limpiar session IDs y restos del agente
        output = re.sub(r'(?im)^\[?.*?session\s*(id)?\s*:.*?\]?\s*$', '', output)
        output = re.sub(r'(?i)\[?\s*session\s*(id)?\s*:[^\]\n]*\]?', '', output)
        # Filtrar outputs de herramientas (JSON, curl, código)
        output = clean_agent_output(output)
        return output.strip()
    except subprocess.TimeoutExpired:
        return "Tardé demasiado, intentalo de nuevo -_-"
    except subprocess.CalledProcessError as e:
        error_msg = e.stderr.strip() if e.stderr else (e.stdout.strip() if e.stdout else str(e))
        return f"Error del agente: {error_msg[:80]}"
    except Exception as e:
        return f"Error: {str(e)[:60]}"


# ─────────────────────────────────────────────────────────────────────────────
#  Auth
# ─────────────────────────────────────────────────────────────────────────────

api_key_header = APIKeyHeader(name="X-API-Key")

def verify_api_key(api_key: str = Security(api_key_header)):
    if api_key != API_KEY:
        raise HTTPException(status_code=403, detail="Acceso denegado: API Key incorrecta")
    return api_key


# ─────────────────────────────────────────────────────────────────────────────
#  Estado global
# ─────────────────────────────────────────────────────────────────────────────

class PetState:
    # Estado observable por el reloj
    emoji            = "0_0"
    notification     = ""
    last_hr          = 0
    last_steps       = 0
    last_update      = 0.0   # Timestamp del último POST /health
    last_seen        = 0.0   # Timestamp de la última actividad del reloj

    # Estado interno del loop proactivo
    last_notif_time  = 0.0   # Última notificación proactiva enviada
    hr_high_count    = 0     # Checks consecutivos con HR elevado
    sedentary_mins   = 0.0   # Minutos acumulados sin movimiento
    steps_baseline   = 0     # Pasos al inicio del período sedentario

state = PetState()


# ─────────────────────────────────────────────────────────────────────────────
#  Loop proactivo
# ─────────────────────────────────────────────────────────────────────────────

NOTIF_COOLDOWN_S        = 600   # 10 min mínimo entre notificaciones
CHECK_INTERVAL_S        = 60    # Verificar cada 60 segundos
HR_THRESHOLD            = 110   # BPM considerado elevado
SEDENTARY_THRESHOLD_MIN = 45    # Minutos sin moverse para alertar
WATCH_TIMEOUT_S         = 1800  # Sin señal del reloj en 30 min → no molestar


async def proactive_loop():
    await asyncio.sleep(30)  # Espera inicial

    while True:
        await asyncio.sleep(CHECK_INTERVAL_S)
        now = time.time()

        # — Reloj sin actividad reciente o sin datos —
        if state.last_seen == 0 or (now - state.last_seen) > WATCH_TIMEOUT_S:
            continue

        # — Cooldown anti-spam —
        if (now - state.last_notif_time) < NOTIF_COOLDOWN_S:
            continue

        # ── 1. ALERTA DE RITMO CARDÍACO ELEVADO ───────────────────────────
        if state.last_hr > HR_THRESHOLD:
            state.hr_high_count += 1
            if state.hr_high_count >= 2:  # ~2 min consecutivos
                prompt = (
                    f"Datos actuales: ritmo cardíaco = {state.last_hr} BPM (elevado), "
                    f"pasos = {state.last_steps}. "
                    "El usuario lleva varios minutos con ritmo cardíaco alto. "
                    "Manda un mensaje breve y genuino preguntando si está bien. Máx 1 línea."
                )
                msg = await asyncio.to_thread(ask_agent, prompt)
                _, clean = extract_emotion_and_clean_text(msg)
                state.notification = clean
                state.emoji = "O_O"
                state.last_notif_time = now
                state.hr_high_count = 0
        else:
            state.hr_high_count = 0

        # ── 2. ALERTA DE SEDENTARISMO ──────────────────────────────────────
        if state.last_steps > 0 and (now - state.last_update) < 3600:
            if state.steps_baseline == 0:
                state.steps_baseline = state.last_steps

            if state.last_steps == state.steps_baseline:
                state.sedentary_mins += CHECK_INTERVAL_S / 60

                if state.sedentary_mins >= SEDENTARY_THRESHOLD_MIN:
                    prompt = (
                        f"El usuario lleva aproximadamente {int(state.sedentary_mins)} minutos "
                        "sin moverse. Mándale un recordatorio simpático y breve para que haga "
                        "una pausa activa o se estire. Máx 1 línea."
                    )
                    msg = await asyncio.to_thread(ask_agent, prompt)
                    emoji, clean = extract_emotion_and_clean_text(msg)
                    state.notification = clean
                    state.emoji = emoji
                    state.last_notif_time = now
                    state.sedentary_mins = 0
            else:
                state.sedentary_mins = 0
                state.steps_baseline = state.last_steps


# ─────────────────────────────────────────────────────────────────────────────
#  FastAPI app con lifespan
# ─────────────────────────────────────────────────────────────────────────────

@asynccontextmanager
async def lifespan(app: FastAPI):
    task = asyncio.create_task(proactive_loop())
    yield
    task.cancel()
    try:
        await task
    except asyncio.CancelledError:
        pass

app = FastAPI(title="AgentPet Bridge", lifespan=lifespan)


# ─────────────────────────────────────────────────────────────────────────────
#  Modelos
# ─────────────────────────────────────────────────────────────────────────────

class HealthData(BaseModel):
    heart_rate: int
    steps: int

class ChatResponse(BaseModel):
    response: str
    emoji: str
    vibrate: Optional[int] = 0

class TextChat(BaseModel):
    message: str

class NotifyData(BaseModel):
    message: str
    emoji: str = "O_O"


# ─────────────────────────────────────────────────────────────────────────────
#  Endpoints
# ─────────────────────────────────────────────────────────────────────────────

@app.get("/")
async def root():
    return {"status": "online", "agent": os.environ.get("AGENT_NAME", "AgentPet")}


@app.post("/health", dependencies=[Depends(verify_api_key)])
async def update_health(data: HealthData):
    state.last_hr     = data.heart_rate
    state.last_steps  = data.steps
    state.last_update = time.time()
    state.last_seen   = time.time()

    if state.last_hr > 110:
        state.emoji = "O_O"
    elif 0 < state.last_hr < 50:
        state.emoji = "-_-"

    return {"status": "ok", "mood": state.emoji, "is_active": True}


@app.post("/notify", dependencies=[Depends(verify_api_key)])
async def push_notification(data: NotifyData):
    state.notification = data.message
    state.emoji = data.emoji
    return {"status": "Notification buffered for Watch delivery"}


@app.get("/status", dependencies=[Depends(verify_api_key)])
async def get_status():
    """Estado completo del usuario — para que el agente pueda responder preguntas de salud."""
    now = time.time()
    return {
        "heart_rate":   state.last_hr,
        "steps":        state.last_steps,
        "emoji":        state.emoji,
        "watch_active": state.last_seen > 0 and (now - state.last_seen) < WATCH_TIMEOUT_S,
        "data_age_min": round((now - state.last_update) / 60, 1) if state.last_update > 0 else None,
        "sedentary_min": round(state.sedentary_mins, 1),
    }


@app.post("/proactive/test", dependencies=[Depends(verify_api_key)])
async def test_proactive(context: Optional[str] = None):
    """Fuerza una ejecución inmediata del loop proactivo. Útil para testear.
    Pasa 'context' para darle al agente un escenario específico."""
    prompt = context or (
        f"Datos actuales: ritmo cardíaco = {state.last_hr} BPM, "
        f"pasos hoy = {state.last_steps}, "
        f"minutos sin moverse = {int(state.sedentary_mins)}. "
        "Dame un mensaje proactivo breve y relevante para mandar al usuario en su reloj. Máx 1 línea."
    )
    msg = await asyncio.to_thread(ask_agent, prompt)
    emoji, clean = extract_emotion_and_clean_text(msg)
    state.notification = clean
    state.emoji = emoji
    state.last_notif_time = time.time()
    return {"triggered": True, "message": clean, "emoji": emoji}


@app.get("/mood", dependencies=[Depends(verify_api_key)])
async def get_mood():
    state.last_seen = time.time()
    notif = state.notification
    state.notification = ""  # Consumir — solo se entrega una vez
    return {"emoji": state.emoji, "notification": notif}


def extract_emotion_and_clean_text(text: str) -> tuple[str, str]:
    emojis_map = {
        "^_^": "^_^", "u_u": "u_u", ">_<": ">_<",
        "O_O": "O_O", "o_o": "O_O", "♥_♥": "♥_♥", "-_-": "-_-"
    }

    for raw_emoji, formatted_emoji in emojis_map.items():
        if raw_emoji in text:
            return formatted_emoji, text.replace(raw_emoji, "").strip()

    text_lower = text.lower()
    if any(w in text_lower for w in ["feliz", "bien", "jaja", "genial", "excelente", "alegre", "buen", "estupendo", "gran"]):
        return "^_^", text
    elif any(w in text_lower for w in ["triste", "mal", "perdón", "lo siento", "lamentable", "error", "fallo", "problema", "dolor"]):
        return "u_u", text
    elif any(w in text_lower for w in ["enojo", "odio", "maldit", "peligro", "no me gusta", "detesto"]):
        return ">_<", text
    elif any(w in text_lower for w in ["wow", "guau", "increíble", "sorpresa", "oh", "asombroso", "mira"]):
        return "O_O", text
    elif any(w in text_lower for w in ["amor", "cariño", "lindo", "abrazo", "amigo", "hermoso", "precioso", "corazón"]):
        return "♥_♥", text
    elif "?" in text:
        return "0_?", text

    return "0_0", text


@app.post("/chat", response_model=ChatResponse, dependencies=[Depends(verify_api_key)])
async def text_chat(data: TextChat):
    state.last_seen = time.time()
    agent_response = await asyncio.to_thread(ask_agent, data.message)
    expresion, clean_text = extract_emotion_and_clean_text(agent_response)
    state.emoji = expresion

    vib_map = {
        "^_^": 150, "u_u": 300, ">_<": 500,
        "O_O": 50,  "♥_♥": 200, "0_?": 100, "0_0": 80, "-_-": 80
    }

    return {
        "response": clean_text,
        "emoji": expresion,
        "vibrate": vib_map.get(expresion, 80)
    }


@app.post("/voice-chat", response_model=ChatResponse, dependencies=[Depends(verify_api_key)])
async def voice_chat(audio: UploadFile = File(...)):
    temp_filename = f"tmp_{uuid.uuid4()}.wav"
    with open(temp_filename, "wb") as f:
        f.write(await audio.read())

    try:
        transcription = await asyncio.to_thread(engine.transcribe, temp_filename)

        if not transcription:
            raise HTTPException(status_code=400, detail="Could not transcribe audio")

        agent_response = await asyncio.to_thread(ask_agent, transcription)
        os.remove(temp_filename)

        return {
            "response": agent_response,
            "emoji": state.emoji,
            "vibrate": 100 if "alerta" in agent_response.lower() else 0
        }
    except Exception as e:
        if os.path.exists(temp_filename):
            os.remove(temp_filename)
        raise HTTPException(status_code=500, detail=str(e))


if __name__ == "__main__":
    import uvicorn
    uvicorn.run(app, host="0.0.0.0", port=8000)
