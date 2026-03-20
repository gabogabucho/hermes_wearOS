import os
import uuid
import time
import subprocess
from fastapi import FastAPI, UploadFile, File, HTTPException, Depends, Security
from fastapi.security import APIKeyHeader
from pydantic import BaseModel
from typing import Optional, Dict
from transcription import engine

# Interaction with Hermes via CLI
def ask_hermes(text: str) -> str:
    try:
        # We run the 'hermes' command as a subprocess
        # Adjust command if your hermes installation uses a different alias
        result = subprocess.run(
            ["hermes", "chat", "--text", text],
            capture_output=True,
            text=True,
            check=True
        )
        return result.stdout.strip()
    except Exception as e:
        return f"Error al contactar con Hermes: {str(e)}"

API_KEY = os.environ.get("HERMES_API_KEY", "hermes_secreto_123")
api_key_header = APIKeyHeader(name="X-API-Key")

def verify_api_key(api_key: str = Security(api_key_header)):
    if api_key != API_KEY:
        raise HTTPException(status_code=403, detail="Acceso denegado: API Key incorrecta")
    return api_key

app = FastAPI(title="Hermes Wear OS Bridge")

# Global state for the virtual pet
class PetState:
    emoji = "0_0"
    last_hr = 0
    last_steps = 0
    last_update = time.time()

state = PetState()

class HealthData(BaseModel):
    heart_rate: int
    steps: int

class ChatResponse(BaseModel):
    response: str
    emoji: str
    vibrate: Optional[int] = 0

class TextChat(BaseModel):
    message: str

@app.get("/")
async def root():
    return {"status": "online", "agent": "Hermes"}

@app.post("/health", dependencies=[Depends(verify_api_key)])
async def update_health(data: HealthData):
    state.last_hr = data.heart_rate
    state.last_steps = data.steps
    state.last_update = time.time()
    
    # Simple logic for emoji updates
    if state.last_hr > 110:
        state.emoji = "o_O"
    elif state.last_hr < 50 and state.last_hr > 0:
        state.emoji = "-_-"
    else:
        state.emoji = "0_0"
        
    return {"status": "ok", "mood": state.emoji}

@app.get("/mood", dependencies=[Depends(verify_api_key)])
async def get_mood():
    return {"emoji": state.emoji}

@app.post("/chat", response_model=ChatResponse, dependencies=[Depends(verify_api_key)])
async def text_chat(data: TextChat):
    agent_response = ask_hermes(data.message)
    return {
        "response": agent_response,
        "emoji": state.emoji,
        "vibrate": 100 if "alerta" in agent_response.lower() else 0
    }

@app.post("/voice-chat", response_model=ChatResponse, dependencies=[Depends(verify_api_key)])
async def voice_chat(audio: UploadFile = File(...)):
    # 1. Save audio temporarily
    temp_filename = f"tmp_{uuid.uuid4()}.wav"
    with open(temp_filename, "wb") as f:
        f.write(await audio.read())
    
    try:
        # 2. Transcription using native Hermes engine setup
        transcription = engine.transcribe(temp_filename)
        
        if not transcription:
            raise HTTPException(status_code=400, detail="Could not transcribe audio")

        # 3. Process with Hermes CLI
        agent_response = ask_hermes(transcription)
        
        # 4. Clean up
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
