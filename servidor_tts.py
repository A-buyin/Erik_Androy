"""Servidor de VOZ CLONADA para Erik  (se ejecuta en el VPS, no en el teléfono).

Convierte texto -> audio WAV con TU voz, usando Coqui XTTS-v2, que clona una
voz "zero-shot" a partir de una sola muestra corta (no hace falta entrenar).

────────────────────────────────────────────────────────────────────────────
INSTALACIÓN (en el VPS, con Python 3.10 u 3.11):

    pip install coqui-tts fastapi "uvicorn[standard]"

  Requisitos: ~4 GB de RAM libres. Con GPU es casi instantáneo; en CPU tarda
  unos segundos por frase (aceptable para un asistente).
  Nota: XTTS-v2 usa la "Coqui Public Model License" (uso personal / no comercial).

PASOS:
  1) Graba 15-40 s de TU voz hablando claro y natural. Guárdalo como
     `mi_voz.wav` (mono, 16 kHz o más) junto a este archivo.
  2) Arranca el servidor:
         VOZ_SAMPLE=mi_voz.wav uvicorn servidor_tts:app --host 0.0.0.0 --port 5002
     (La primera vez descargará el modelo XTTS; puede tardar unos minutos.)
  3) Exponlo detrás de tu proxy Caddy con la MISMA auth que Ollama, p. ej.:
         tu-vps.dominio {
             handle /tts* {
                 basic_auth { erik <hash-de-tu-contraseña> }
                 reverse_proxy localhost:5002
             }
         }
  4) En el teléfono, en errik_config.json añade:
         "voz_url": "https://tu-vps.dominio/tts"
     (usuario/contraseña se reutilizan de la config de Ollama).
────────────────────────────────────────────────────────────────────────────
"""
import os
import tempfile

from fastapi import FastAPI
from fastapi.responses import Response
from pydantic import BaseModel
from TTS.api import TTS

MUESTRA = os.environ.get("VOZ_SAMPLE", "mi_voz.wav")
IDIOMA = os.environ.get("VOZ_IDIOMA", "es")
MODELO = os.environ.get("VOZ_MODELO", "tts_models/multilingual/multi-dataset/xtts_v2")
# Voz INTEGRADA de XTTS (p.ej. "Luis Moray"). Si se define, se usa en vez de la
# muestra clonada; útil para una voz de estudio sin grabar nada.
SPEAKER = os.environ.get("VOZ_SPEAKER", "").strip()

# Solo se exige la muestra si NO se usa una voz integrada.
if not SPEAKER and not os.path.exists(MUESTRA):
    raise SystemExit(f"No encuentro la muestra de voz: {MUESTRA}. "
                     f"Graba 15-40 s de tu voz y guárdala ahí (o define VOZ_SAMPLE), "
                     f"o define VOZ_SPEAKER con una voz integrada de XTTS.")

print(f"Cargando XTTS ({MODELO})... la primera vez puede tardar.")
_tts = TTS(MODELO)  # descarga el modelo la primera vez y lo mantiene en memoria

app = FastAPI(title="Erik TTS - voz clonada")


class Peticion(BaseModel):
    text: str


@app.get("/salud")
def salud():
    return {"ok": True, "voz": SPEAKER or MUESTRA, "integrada": bool(SPEAKER), "idioma": IDIOMA}


@app.post("/tts")
def tts(pet: Peticion):
    texto = (pet.text or "").strip()
    if not texto:
        return Response(status_code=204)
    with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as f:
        ruta = f.name
    try:
        if SPEAKER:
            _tts.tts_to_file(text=texto, speaker=SPEAKER,
                             language=IDIOMA, file_path=ruta)
        else:
            _tts.tts_to_file(text=texto, speaker_wav=MUESTRA,
                             language=IDIOMA, file_path=ruta)
        with open(ruta, "rb") as fh:
            audio = fh.read()
    finally:
        try:
            os.remove(ruta)
        except OSError:
            pass
    return Response(content=audio, media_type="audio/wav")
