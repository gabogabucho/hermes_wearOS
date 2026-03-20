import asyncio
import json
import os
import re
import subprocess
import time
import uuid
from contextlib import asynccontextmanager
from dataclasses import dataclass, field
from datetime import datetime
from typing import Optional
from urllib.parse import quote
from urllib.request import urlopen

from fastapi import Depends, FastAPI, File, HTTPException, Security, UploadFile
from fastapi.security import APIKeyHeader
from pydantic import BaseModel

from transcription import engine


@dataclass
class GatewayConfig:
    agent_cmd: list[str] = field(default_factory=lambda: os.environ.get("AGENT_CMD", "hermes chat -Q -q").split())
    agent_resume_cmd_template: str = os.environ.get(
        "AGENT_RESUME_CMD",
        "hermes chat --resume {session_id} -Q -q",
    )
    api_key: str = (
        os.environ.get("AGENT_API_KEY")
        or os.environ.get("HERMES_API_KEY")
        or "agentpet_secreto_123"
    )
    weather_location: str = os.environ.get("WEATHER_LOCATION", "Buenos Aires")
    weather_cache_s: int = int(os.environ.get("WEATHER_CACHE_S", "900"))
    agent_timeout_s: int = int(os.environ.get("AGENT_TIMEOUT_S", "25"))
    watch_timeout_s: int = int(os.environ.get("WATCH_TIMEOUT_S", "1800"))
    notif_cooldown_s: int = int(os.environ.get("NOTIF_COOLDOWN_S", "600"))
    check_interval_s: int = int(os.environ.get("CHECK_INTERVAL_S", "60"))
    hr_threshold: int = int(os.environ.get("HR_THRESHOLD", "110"))
    sedentary_threshold_min: int = int(os.environ.get("SEDENTARY_THRESHOLD_MIN", "45"))
    session_map_path: str = os.environ.get(
        "SESSION_MAP_PATH",
        os.path.join(os.path.dirname(__file__), "watch_sessions.json"),
    )
    watch_platform_hint: str = os.environ.get(
        "WATCH_PLATFORM_HINT",
        (
            "This session is coming from a Wear OS watch. Stay fully yourself, with your normal identity, memory, "
            "skills, and tools. But adapt your style for the watch: be more direct, concise, expressive, and warm. "
            "Prefer one or two short lines unless the user explicitly asks for more detail. Avoid summaries, meta "
            "commentary, and long preambles. If it fits naturally, you may use simple watch-safe emotive text like "
            "^_^, O_O, -_-, u_u, >_<, or 0_? at the end."
        ),
    )


CONFIG = GatewayConfig()


HEALTH_TRIGGERS = [
    "salud", "pulso", "pulsaciones", "ritmo cardiaco", "ritmo cardíaco",
    "corazon", "corazón", "heart rate", "bpm", "pasos", "camine",
    "caminé", "estres", "estrés", "sedent", "bienestar", "descanso",
]


def is_health_query(text: str) -> bool:
    prompt = text.lower()
    return any(trigger in prompt for trigger in HEALTH_TRIGGERS)


def is_time_query(text: str) -> bool:
    prompt = text.lower()
    triggers = [
        "hora", "que hora", "qué hora", "hora es", "time",
        "son las", "decime la hora", "dime la hora",
    ]
    return any(trigger in prompt for trigger in triggers)


def is_weather_query(text: str) -> bool:
    prompt = text.lower()
    triggers = [
        "clima", "tiempo", "temperatura", "llueve", "lluvia",
        "pronostico", "pronóstico", "hace calor", "hace frio",
        "hace frío", "weather",
    ]
    return any(trigger in prompt for trigger in triggers)

def clean_agent_output(text: str) -> str:
    import json as _json

    clean_lines: list[str] = []
    in_json_block = False
    in_summary_block = False

    for line in text.splitlines():
        stripped = line.strip()
        normalized = stripped.lower()
        if not stripped and not clean_lines:
            continue

        if stripped.startswith(("{", "[")):
            try:
                _json.loads(stripped)
                continue
            except _json.JSONDecodeError:
                in_json_block = True
                continue

        if in_json_block:
            if stripped.endswith(("}", "]")):
                in_json_block = False
            continue

        if re.match(r'^"?\w+"?\s*:\s*', stripped) and not re.search(r"[áéíóúñ¿¡]", stripped, re.IGNORECASE):
            continue
        if stripped.startswith("┊") or stripped.startswith("|"):
            continue
        if re.match(r"(?i)^(session|conversation|context)\s+(summary|resume|recap)\s*:?\s*$", stripped):
            in_summary_block = True
            continue
        if re.match(r"(?i)^resumen(\s+de\s+la\s+sesion|\s+de\s+la\s+sesión|\s+del\s+contexto)?\s*:?\s*$", stripped):
            in_summary_block = True
            continue
        if in_summary_block:
            if not stripped:
                in_summary_block = False
            continue
        if normalized.startswith(("summary:", "session summary:", "conversation summary:", "context summary:", "resume:", "recap:")):
            continue
        if normalized.startswith(("resumen:", "resumen de la sesion:", "resumen de la sesión:", "resumen del contexto:")):
            continue
        if re.match(r"(?i)^\[?\s*(session|tool_call|function_call|tool)\s*(id)?\s*:", stripped):
            continue
        if re.match(r"(?i)^session[_\s-]*id\s*:", stripped):
            continue
        if stripped.startswith("$") or stripped.startswith("`$"):
            continue
        if stripped.startswith("```"):
            continue

        clean_lines.append(line)

    result = "\n".join(clean_lines).strip()
    return result if len(result) > 3 else text.strip()


def extract_emotion_and_clean_text(text: str) -> tuple[str, str]:
    emojis_map = {
        "^_^": "^_^",
        "u_u": "u_u",
        ">_<": ">_<",
        "O_O": "O_O",
        "o_o": "O_O",
        "♥_♥": "♥_♥",
        "â™¥_â™¥": "♥_♥",
        "-_-": "-_-",
        "0_?": "0_?",
    }

    for raw_emoji, formatted_emoji in emojis_map.items():
        if raw_emoji in text:
            return formatted_emoji, text.replace(raw_emoji, "").strip()

    text_lower = text.lower()
    if any(w in text_lower for w in ["feliz", "bien", "jaja", "genial", "excelente", "alegre", "buen", "estupendo", "gran"]):
        return "^_^", text
    if any(w in text_lower for w in ["triste", "mal", "perdón", "lo siento", "lamentable", "error", "fallo", "problema", "dolor"]):
        return "u_u", text
    if any(w in text_lower for w in ["enojo", "odio", "maldit", "peligro", "no me gusta", "detesto"]):
        return ">_<", text
    if any(w in text_lower for w in ["wow", "guau", "increíble", "sorpresa", "oh", "asombroso", "mira"]):
        return "O_O", text
    if any(w in text_lower for w in ["amor", "cariño", "lindo", "abrazo", "amigo", "hermoso", "precioso", "corazón"]):
        return "♥_♥", text
    if "?" in text:
        return "0_?", text
    return "0_0", text


def vibration_for_emoji(emoji: str) -> int:
    vib_map = {
        "^_^": 150,
        "u_u": 300,
        ">_<": 500,
        "O_O": 50,
        "♥_♥": 200,
        "0_?": 100,
        "0_0": 80,
        "-_-": 80,
        "._.": 90,
    }
    return vib_map.get(emoji, 80)


@dataclass
class RuntimeState:
    emoji: str = "0_0"
    notification: str = ""
    last_hr: int = 0
    last_steps: int = 0
    last_update: float = 0.0
    last_seen: float = 0.0
    last_notif_time: float = 0.0
    hr_high_count: int = 0
    sedentary_mins: float = 0.0
    steps_baseline: int = 0
    weather_cache: Optional[dict] = None
    weather_cached_at: float = 0.0


state = RuntimeState()


def build_health_context() -> str:
    now = time.time()
    if state.last_update == 0:
        return "SALUD: sin datos aún. "
    age_min = round((now - state.last_update) / 60, 1)
    hr_note = f"{state.last_hr} BPM" if state.last_hr > 0 else "sin lectura"
    sed_note = f"{int(state.sedentary_mins)} min sin moverse" if state.sedentary_mins > 0 else "activo"
    return (
        f"SALUD ACTUAL (hace {age_min} min): "
        f"ritmo cardíaco = {hr_note}, pasos hoy = {state.last_steps}, {sed_note}. "
    )


@dataclass
class HermesSessionRef:
    hermes_session_id: str
    last_active: float


class HermesSessionStore:
    def __init__(self, path: str) -> None:
        self.path = path
        self._sessions: dict[str, HermesSessionRef] = {}
        self._load()

    def _load(self) -> None:
        if not os.path.exists(self.path):
            return
        try:
            with open(self.path, "r", encoding="utf-8") as handle:
                payload = json.load(handle)
        except Exception:
            return

        for session_key, item in payload.items():
            session_id = item.get("hermes_session_id")
            if not session_id:
                continue
            self._sessions[session_key] = HermesSessionRef(
                hermes_session_id=session_id,
                last_active=float(item.get("last_active", time.time())),
            )

    def _save(self) -> None:
        payload = {
            key: {
                "hermes_session_id": value.hermes_session_id,
                "last_active": value.last_active,
            }
            for key, value in self._sessions.items()
        }
        with open(self.path, "w", encoding="utf-8") as handle:
            json.dump(payload, handle, ensure_ascii=True, indent=2)

    def get(self, session_key: str) -> Optional[HermesSessionRef]:
        ref = self._sessions.get(session_key)
        if ref is not None:
            ref.last_active = time.time()
            self._save()
        return ref

    def set(self, session_key: str, hermes_session_id: str) -> None:
        self._sessions[session_key] = HermesSessionRef(
            hermes_session_id=hermes_session_id,
            last_active=time.time(),
        )
        self._save()

    def reset(self, session_key: str) -> None:
        if session_key in self._sessions:
            self._sessions.pop(session_key, None)
            self._save()

    def status(self) -> dict:
        now = time.time()
        return {
            key: {
                "hermes_session_id": value.hermes_session_id,
                "idle_s": round(now - value.last_active, 1),
            }
            for key, value in self._sessions.items()
        }


session_store = HermesSessionStore(CONFIG.session_map_path)


class QuickIntentRouter:
    def quick_time_reply(self) -> tuple[str, str, int]:
        now = datetime.now()
        text = f"Son las {now.strftime('%H:%M')}."
        if 6 <= now.hour < 12:
            return text, "^_^", 70
        if 12 <= now.hour < 20:
            return text, "0_0", 70
        return text, "-_-", 90

    def fetch_weather_snapshot(self) -> dict:
        now = time.time()
        if state.weather_cache and (now - state.weather_cached_at) < CONFIG.weather_cache_s:
            return state.weather_cache

        location = quote(CONFIG.weather_location)
        with urlopen(f"https://wttr.in/{location}?format=j1", timeout=4) as response:
            payload = json.loads(response.read().decode("utf-8"))

        current = payload["current_condition"][0]
        snapshot = {
            "temp_c": current.get("temp_C", "?"),
            "desc": current.get("weatherDesc", [{"value": "despejado"}])[0].get("value", "despejado").lower(),
        }
        state.weather_cache = snapshot
        state.weather_cached_at = now
        return snapshot

    def quick_weather_reply(self) -> tuple[str, str, int]:
        try:
            weather = self.fetch_weather_snapshot()
            desc = weather["desc"]
            text = f"{weather['temp_c']}°C, {desc}."
            if any(word in desc for word in ["sun", "sole", "clear", "despejado"]):
                return text, "^_^", 60
            if any(word in desc for word in ["rain", "lluv", "storm", "torment"]):
                return text, "._.", 100
            if any(word in desc for word in ["cloud", "nublado", "mist", "fog"]):
                return text, "-_-", 60
            return text, "0_0", 60
        except Exception:
            return "No pude ver el clima.", "0_?", 120

    def maybe_handle(self, text: str) -> Optional[tuple[str, str, int]]:
        if is_time_query(text):
            return self.quick_time_reply()
        if is_weather_query(text):
            return self.quick_weather_reply()
        return None


quick_router = QuickIntentRouter()


class AgentGateway:
    def build_watch_message(self, user_text: str, include_health: bool) -> str:
        parts: list[str] = []
        if CONFIG.watch_platform_hint:
            parts.append(f"[watch-platform]\n{CONFIG.watch_platform_hint}")
        if include_health:
            parts.append("[watch-local-health]\n" + build_health_context().strip())
        parts.append(user_text)
        return "\n\n".join(part for part in parts if part).strip()

    def build_resume_cmd(self, hermes_session_id: str) -> list[str]:
        return CONFIG.agent_resume_cmd_template.format(session_id=hermes_session_id).split()

    def run_hermes(self, command: list[str], message: str) -> tuple[str, Optional[str]]:
        result = subprocess.run(
            command + [message],
            capture_output=True,
            text=True,
            check=True,
            stdin=subprocess.DEVNULL,
            timeout=CONFIG.agent_timeout_s,
        )
        output = result.stdout.strip()
        session_match = re.search(r"(?im)^session[_\s-]*id\s*:\s*([^\s]+)\s*$", output)
        hermes_session_id = session_match.group(1).strip() if session_match else None
        output = re.sub(r"(?im)^\[?.*?session[_\s-]*(id)?\s*:.*?\]?\s*$", "", output)
        output = re.sub(r"(?i)\[?\s*session[_\s-]*(id)?\s*:[^\]\n]*\]?", "", output)
        return clean_agent_output(output).strip(), hermes_session_id

    async def ask(
        self,
        text: str,
        session_key: str = "watch-main",
        allow_session: bool = True,
    ) -> tuple[str, str, int]:
        quick = quick_router.maybe_handle(text)
        if quick is not None:
            return quick

        include_health = is_health_query(text)
        message = self.build_watch_message(text, include_health)
        existing_session = session_store.get(session_key) if allow_session else None
        command = (
            self.build_resume_cmd(existing_session.hermes_session_id)
            if existing_session is not None
            else CONFIG.agent_cmd
        )

        try:
            raw, hermes_session_id = await asyncio.to_thread(self.run_hermes, command, message)
        except subprocess.TimeoutExpired:
            return "Tardé demasiado, intentá de nuevo.", "-_-", 140
        except subprocess.CalledProcessError as exc:
            error_msg = exc.stderr.strip() if exc.stderr else (exc.stdout.strip() if exc.stdout else str(exc))
            invalid_resume = existing_session is not None and any(
                token in error_msg.lower()
                for token in ["resume", "session", "not found", "unknown"]
            )
            if invalid_resume:
                session_store.reset(session_key)
                try:
                    raw, hermes_session_id = await asyncio.to_thread(self.run_hermes, CONFIG.agent_cmd, message)
                except Exception:
                    return f"Error del agente: {error_msg[:80]}", "0_?", 120
            else:
                return f"Error del agente: {error_msg[:80]}", "0_?", 120
        except Exception as exc:
            return f"Error: {str(exc)[:60]}", "0_?", 120

        emoji, clean_text = extract_emotion_and_clean_text(raw)
        if allow_session:
            resolved_session_id = hermes_session_id or (existing_session.hermes_session_id if existing_session else None)
            if resolved_session_id:
                session_store.set(session_key, resolved_session_id)
        state.emoji = emoji
        return clean_text, emoji, vibration_for_emoji(emoji)


gateway = AgentGateway()


async def proactive_loop() -> None:
    await asyncio.sleep(30)
    while True:
        await asyncio.sleep(CONFIG.check_interval_s)
        now = time.time()

        if state.last_seen == 0 or (now - state.last_seen) > CONFIG.watch_timeout_s:
            continue
        if (now - state.last_notif_time) < CONFIG.notif_cooldown_s:
            continue

        if state.last_hr > CONFIG.hr_threshold:
            state.hr_high_count += 1
            if state.hr_high_count >= 2:
                prompt = (
                    f"Datos actuales: ritmo cardíaco = {state.last_hr} BPM (elevado), "
                    f"pasos = {state.last_steps}. "
                    "El usuario lleva varios minutos con ritmo cardíaco alto. "
                    "Mandá un mensaje breve y genuino preguntando si está bien."
                )
                clean, _, _ = await gateway.ask(prompt, session_key="watch-system", allow_session=False)
                state.notification = clean
                state.emoji = "O_O"
                state.last_notif_time = now
                state.hr_high_count = 0
        else:
            state.hr_high_count = 0

        if state.last_steps > 0 and (now - state.last_update) < 3600:
            if state.steps_baseline == 0:
                state.steps_baseline = state.last_steps

            if state.last_steps == state.steps_baseline:
                state.sedentary_mins += CONFIG.check_interval_s / 60
                if state.sedentary_mins >= CONFIG.sedentary_threshold_min:
                    prompt = (
                        f"El usuario lleva aproximadamente {int(state.sedentary_mins)} minutos sin moverse. "
                        "Mandale un recordatorio simpático y breve para que haga una pausa activa."
                    )
                    clean, emoji, _ = await gateway.ask(prompt, session_key="watch-system", allow_session=False)
                    state.notification = clean
                    state.emoji = emoji
                    state.last_notif_time = now
                    state.sedentary_mins = 0
            else:
                state.sedentary_mins = 0
                state.steps_baseline = state.last_steps


@asynccontextmanager
async def lifespan(app: FastAPI):
    task = asyncio.create_task(proactive_loop())
    yield
    task.cancel()
    try:
        await task
    except asyncio.CancelledError:
        pass


app = FastAPI(title="AgentPet Gateway", lifespan=lifespan)

api_key_header = APIKeyHeader(name="X-API-Key")


def verify_api_key(api_key: str = Security(api_key_header)):
    if api_key != CONFIG.api_key:
        raise HTTPException(status_code=403, detail="Acceso denegado: API Key incorrecta")
    return api_key


class HealthData(BaseModel):
    heart_rate: int
    steps: int


class ChatResponse(BaseModel):
    response: str
    emoji: str
    vibrate: Optional[int] = 0


class TextChat(BaseModel):
    message: str
    session_key: Optional[str] = "watch-main"


class NotifyData(BaseModel):
    message: str
    emoji: str = "O_O"


@app.get("/")
async def root():
    return {
        "status": "online",
        "agent": os.environ.get("AGENT_NAME", "Hermes"),
        "mode": "gateway",
        "backend": "hermes-agent",
        "quick_intents": ["time", "weather"],
    }


@app.get("/gateway/status", dependencies=[Depends(verify_api_key)])
async def gateway_status():
    return {
        "sessions": session_store.status(),
        "watch_active": state.last_seen > 0 and (time.time() - state.last_seen) < CONFIG.watch_timeout_s,
        "backend": "hermes-agent",
        "quick_intents": ["time", "weather"],
        "session_map_path": CONFIG.session_map_path,
        "watch_platform_hint": CONFIG.watch_platform_hint,
    }


@app.post("/gateway/reset-session", dependencies=[Depends(verify_api_key)])
async def reset_session(session_key: str = "watch-main"):
    session_store.reset(session_key)
    return {"ok": True, "session_key": session_key, "reset": True}


@app.post("/health", dependencies=[Depends(verify_api_key)])
async def update_health(data: HealthData):
    state.last_hr = data.heart_rate
    state.last_steps = data.steps
    state.last_update = time.time()
    state.last_seen = time.time()

    if state.last_hr > CONFIG.hr_threshold:
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
    now = time.time()
    return {
        "heart_rate": state.last_hr,
        "steps": state.last_steps,
        "emoji": state.emoji,
        "watch_active": state.last_seen > 0 and (now - state.last_seen) < CONFIG.watch_timeout_s,
        "data_age_min": round((now - state.last_update) / 60, 1) if state.last_update > 0 else None,
        "sedentary_min": round(state.sedentary_mins, 1),
    }


@app.post("/proactive/test", dependencies=[Depends(verify_api_key)])
async def test_proactive(context: Optional[str] = None):
    prompt = context or (
        f"Datos actuales: ritmo cardíaco = {state.last_hr} BPM, "
        f"pasos hoy = {state.last_steps}, "
        f"minutos sin moverse = {int(state.sedentary_mins)}. "
        "Dame un mensaje proactivo breve y relevante para mandar al usuario en su reloj."
    )
    clean, emoji, _ = await gateway.ask(prompt, session_key="watch-system", allow_session=False)
    state.notification = clean
    state.emoji = emoji
    state.last_notif_time = time.time()
    return {"triggered": True, "message": clean, "emoji": emoji}


@app.get("/mood", dependencies=[Depends(verify_api_key)])
async def get_mood():
    state.last_seen = time.time()
    notif = state.notification
    state.notification = ""
    return {"emoji": state.emoji, "notification": notif}


@app.post("/chat", response_model=ChatResponse, dependencies=[Depends(verify_api_key)])
async def text_chat(data: TextChat):
    state.last_seen = time.time()
    clean_text, emoji, vibrate = await gateway.ask(data.message, session_key=data.session_key or "watch-main")
    return {"response": clean_text, "emoji": emoji, "vibrate": vibrate}


@app.post("/voice-chat", response_model=ChatResponse, dependencies=[Depends(verify_api_key)])
async def voice_chat(audio: UploadFile = File(...)):
    temp_filename = f"tmp_{uuid.uuid4()}.wav"
    with open(temp_filename, "wb") as file_handle:
        file_handle.write(await audio.read())

    try:
        transcription = await asyncio.to_thread(engine.transcribe, temp_filename)
        if not transcription:
            raise HTTPException(status_code=400, detail="Could not transcribe audio")

        state.last_seen = time.time()
        clean_text, emoji, vibrate = await gateway.ask(transcription, session_key="watch-main")
        return {"response": clean_text, "emoji": emoji, "vibrate": vibrate}
    except Exception as exc:
        raise HTTPException(status_code=500, detail=str(exc))
    finally:
        if os.path.exists(temp_filename):
            os.remove(temp_filename)


if __name__ == "__main__":
    import uvicorn

    uvicorn.run(app, host="0.0.0.0", port=8000)
