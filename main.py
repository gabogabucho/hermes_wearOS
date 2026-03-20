import os
import json
import uuid
import time
from fastapi import FastAPI, UploadFile, File, HTTPException
from pydantic import BaseModel
from typing import Optional, Dict
from transcription import engine

# Mocking the AI agent import until the environment is set up
# In production: from hermes_agent.run_agent import AIAgent

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

@app.get("/")
async def root():
    return {"status": "online", "agent": "Hermes"}

@app.post("/health")
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

@app.get("/mood")
async def get_mood():
    return {"emoji": state.emoji}

@app.post("/voice-chat", response_model=ChatResponse)
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

        # 3. Process with Hermes
        # agent_response = agent.chat(transcription)
        agent_response = f"Simulado: Recibí tu mensaje '{transcription}'"
        
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
