import os
import torch
from typing import Optional

# Check if faster-whisper is available (standard for Hermes)
try:
    from faster_whisper import WhisperModel
    HAS_WHISPER = True
except ImportError:
    HAS_WHISPER = False

class TranscriptionEngine:
    def __init__(self, model_size="base"):
        self.model = None
        self.model_size = model_size
        if HAS_WHISPER:
            # Optimize for CPU if no GPU
            device = "cuda" if torch.cuda.is_available() else "cpu"
            self.model = WhisperModel(model_size, device=device, compute_type="int8")
            print(f"DEBUG: Loaded Faster-Whisper model '{model_size}' on '{device}'")

    def transcribe(self, audio_path: str) -> Optional[str]:
        if not HAS_WHISPER:
            return "[Error: faster-whisper not installed on VPS]"
        
        segments, info = self.model.transcribe(audio_path, beam_size=5)
        text = " ".join([segment.text for segment in segments])
        return text.strip()

# Singleton engine
engine = TranscriptionEngine()
