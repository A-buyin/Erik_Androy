import os
os.environ["TF_CPP_MIN_LOG_LEVEL"] = "3"
os.environ["TF_ENABLE_ONEDNN_OPTS"] = "0"
os.environ["GRPC_VERBOSITY"] = "ERROR"
os.environ["GLOG_minloglevel"] = "3"

import logging
logging.getLogger("tensorflow").setLevel(logging.ERROR)
logging.getLogger("absl").setLevel(logging.ERROR)

import warnings
warnings.filterwarnings("ignore", category=UserWarning)

import subprocess
import json
import re
import unicodedata
import tempfile
import speech_recognition as sr
import joblib
import requests
from pathlib import Path

# Whisper local opcional (fallback a Google STT si no está disponible)
try:
    from faster_whisper import WhisperModel
    WHISPER_AVAILABLE = True
except Exception:
    WhisperModel = None
    WHISPER_AVAILABLE = False

# Se carga una sola vez (perezosamente) para no reinicializar en cada comando.
_WHISPER_MODEL = None


def _get_whisper_model():
    global _WHISPER_MODEL
    if _WHISPER_MODEL is None:
        _WHISPER_MODEL = WhisperModel("small", device="cpu", compute_type="int8")
    return _WHISPER_MODEL

# 1. Cargar modelos (si existen)
BASE_DIR = Path(__file__).resolve().parent

# IDS Random Forest
modelo = None
modelo_path = BASE_DIR / "Modelos_IDS" / "ids_random_forest.pkl"
try:
    modelo = joblib.load(modelo_path)
    print(f"Modelo IDS cargado correctamente desde: {modelo_path}")
except FileNotFoundError:
    print(f"Advertencia: no se encontró el modelo IDS en '{modelo_path}'.")
except Exception as e:
    print(f"Advertencia al cargar el modelo IDS: {e}")

# Deep Learning 1 (Keras/TensorFlow placeholder)
deep_model_1 = None
deep_model_1_path = BASE_DIR / "Modelos_IDS" / "deep_model_1.h5"
try:
    import tensorflow as tf
    tf.get_logger().setLevel("ERROR")
    try:
        tf.autograph.set_verbosity(0)
    except Exception:
        pass
    deep_model_1 = tf.keras.models.load_model(deep_model_1_path, compile=False)
    deep_model_1.compile(optimizer="adam", loss="sparse_categorical_crossentropy", metrics=["accuracy"])
    print(f"Modelo Deep 1 cargado correctamente desde: {deep_model_1_path}")
except FileNotFoundError:
    print(f"Advertencia: no se encontró el modelo Deep 1 en '{deep_model_1_path}'.")
except ImportError:
    print("Advertencia: TensorFlow no está instalado. Deep 1 no disponible.")
except Exception as e:
    print(f"Advertencia al cargar Deep 1: {e}")

# Deep Learning 2 (PyTorch placeholder)
deep_model_2 = None
deep_model_2_path = BASE_DIR / "Modelos_IDS" / "deep_model_2.pt"
try:
    import torch
    deep_model_2 = torch.load(deep_model_2_path, map_location="cpu")
    print(f"Modelo Deep 2 cargado correctamente desde: {deep_model_2_path}")
except FileNotFoundError:
    print(f"Advertencia: no se encontró el modelo Deep 2 en '{deep_model_2_path}'.")
except ImportError:
    print("Advertencia: PyTorch no está instalado. Deep 2 no disponible.")
except Exception as e:
    print(f"Advertencia al cargar Deep 2: {e}")

# 2. Configuración de Ollama local
OLLAMA_URL = "http://localhost:11434/api/generate"
OLLAMA_MODEL = "llama3.1:8b"

TEXTO_AYUDA = """COMANDOS DISPONIBLES

(Los comandos toleran acentos, mayusculas y errores tipicos de la voz.)

[Telefonía / SMS]  (requiere Termux en Android)
  • llama a [número/contacto]   -> marca via termux-telephony-call
    variantes: llama / llamar / llamada / marca / marcar / telefonea
    con o sin "a"/"al"  (ej: "llama Juan", "marca 300123", "llama al 300...")
  • ultimo mensaje              -> lee el ultimo SMS recibido
    variantes: "ultimo mensaje", "ultimo sms", "lee el ultimo mensaje"

[Modelos IDS / Machine Learning]
  • ejecutar escaneo            -> Random Forest  (scikit-learn)
    variantes: "ejecuta escaner", "ejecutar escanear", "ejecuta el escaneo"
                                  Archivo: Modelos_IDS/ids_random_forest.pkl
  • ejecutar deep uno           -> Red neuronal   (Keras / TensorFlow)
    variantes: deep/dip/dep/the + uno/1/primero  (ej: "ejecuta dip uno")
                                  Archivo: Modelos_IDS/deep_model_1.h5
  • ejecutar deep dos           -> Red neuronal   (PyTorch)
    variantes: deep/dip/dep/the + dos/2/segundo  (ej: "ejecuta dip 2")
                                  Archivo: Modelos_IDS/deep_model_2.pt

[LLM local]
  • pregunta a ollama [texto]   -> consulta a Ollama (modelo llama3.1:8b)
    variantes: "preguntale a ollama...", "consulta a ollama...", "o llama"
                                  Requiere Ollama corriendo en localhost:11434

[Sistema]
  • ayuda  /  help              -> muestra esta lista
  • clear  /  cls  /  limpiar   -> limpia la pantalla
  • salir  /  apagate           -> cierra Errik"""

def consultar_ollama(prompt):
    try:
        payload = {
            "model": OLLAMA_MODEL,
            "prompt": prompt,
            "stream": False
        }
        respuesta = requests.post(OLLAMA_URL, json=payload, timeout=60)
        respuesta.raise_for_status()
        data = respuesta.json()
        return data.get("response", "").strip()
    except Exception as e:
        return f"No pude consultar Ollama. Verifica que esté corriendo. Detalle: {e}"

# Detección tolerante de la orden de llamada.
# Acepta: llama / llamar / llamada / marca / marcar / telefonea(r),
# con o sin "a"/"al" (útil cuando Whisper se come la "a": "llama Juan").
_PATRON_LLAMADA = re.compile(
    r"^\s*(?:llama(?:r|da)?|lama|yama|marca(?:r)?|telefonea(?:r)?)\s+(?:al?\s+)?(.+)$",
    re.IGNORECASE,
)


def extraer_objetivo_llamada(cmd):
    m = _PATRON_LLAMADA.match(cmd)
    if not m:
        return None
    objetivo = m.group(1).strip().strip(".,;:!?¿¡").strip()
    return objetivo or None


def normalizar(texto):
    """Minúsculas, sin acentos y espacios colapsados, para comparar comandos
    tolerando errores de transcripción de voz."""
    t = unicodedata.normalize("NFD", texto or "")
    t = "".join(c for c in t if unicodedata.category(c) != "Mn")
    return re.sub(r"\s+", " ", t).lower().strip()


# Patrones tolerantes para los comandos de ML / LLM (sobre texto normalizado).
# Aceptan variantes que suele producir Whisper:
#   ejecutar -> ejecuta / ejecuto ;  escaneo -> escaner / escanear
#   deep -> dip / dep / dib / the ;  uno/dos -> 1/2 / primero/segundo
_ART = r"(?:(?:el|la|un|una|los|las)\s+)?"  # artículo opcional intermedio
_PATRON_ESCANEO = re.compile(r"\bejecut\w*\s+" + _ART + r"escan\w*")
_PATRON_DEEP1 = re.compile(r"\bejecut\w*\s+" + _ART + r"(?:deep|dip|dep|dib|the)\s+(?:uno|1|primero)\b")
_PATRON_DEEP2 = re.compile(r"\bejecut\w*\s+" + _ART + r"(?:deep|dip|dep|dib|the)\s+(?:dos|2|segundo)\b")
_PATRON_ULTIMO_MSJ = re.compile(r"\bultim\w*\s+(?:mensaje|mensajes|sms)")
# Ollama: se aplica sobre el texto normalizado (sin acentos) para tolerar
# variantes como "pregúntale"/"o llama". Captura lo que sigue como prompt.
_PATRON_OLLAMA = re.compile(
    r"(?:pregunt\w*|consult\w*)\s+a\s+(?:o\s*llama|ollama|olama|oyama|llama)\b\s*(.*)$",
)


def hablar(texto):
    print(f"Errik: {texto}")
    # Solo intenta TTS si Termux está disponible (Android). Usa subprocess para
    # evitar problemas de shell/comillas dentro del texto.
    import shutil
    if shutil.which("termux-tts-speak"):
        try:
            subprocess.run(["termux-tts-speak", texto], check=False)
        except Exception:
            pass

def transcribir_con_whisper(audio_data):
    if not WHISPER_AVAILABLE:
        return ""

    temp_path = None
    try:
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
            temp_path = tmp.name

        with open(temp_path, "wb") as f:
            f.write(audio_data.get_wav_data())

        model = _get_whisper_model()
        segments, _ = model.transcribe(temp_path, language="es")
        texto = " ".join(seg.text.strip() for seg in segments if seg.text.strip()).strip()
        return texto
    except Exception:
        return ""
    finally:
        try:
            if temp_path and os.path.exists(temp_path):
                os.remove(temp_path)
        except Exception:
            pass


def transcribir_audio(audio_data):
    # Prioridad: Whisper local
    texto = transcribir_con_whisper(audio_data)
    if texto:
        return texto

    # Fallback: Google STT (requiere internet)
    reconocedor = sr.Recognizer()
    try:
        texto = reconocedor.recognize_google(audio_data, language="es-ES")
        return texto.strip()
    except Exception:
        return ""


def escuchar_comando():
    reconocedor = sr.Recognizer()
    try:
        with sr.Microphone() as source:
            print("Errik está escuchando...")
            reconocedor.adjust_for_ambient_noise(source, duration=0.5)
            audio = reconocedor.listen(source, timeout=5, phrase_time_limit=10)
    except Exception:
        # Timeout sin voz, micrófono no disponible, etc.
        return ""

    comando = transcribir_audio(audio)
    if comando:
        print(f"Dijiste: {comando}")
        return comando.lower()
    return ""

def procesar(comando):
    cmd = comando.lower().strip()
    cmd_norm = normalizar(cmd)  # sin acentos/mayúsculas, para tolerar la voz

    # AYUDA
    if cmd in ("ayuda", "help", "?") or cmd.startswith("ayuda ") or cmd.startswith("help "):
        print(TEXTO_AYUDA)
        return True

    # LIMPIAR PANTALLA
    if cmd in ("clear", "cls", "limpiar", "limpia"):
        os.system("cls" if os.name == "nt" else "clear")
        return True

    ollama_match = _PATRON_OLLAMA.search(cmd_norm)

    # ORDEN DE LLAMADA
    objetivo_llamada = extraer_objetivo_llamada(cmd)
    if objetivo_llamada:
        hablar(f"Entendido. Marcando a {objetivo_llamada}")
        subprocess.run(['termux-telephony-call', objetivo_llamada])

    # ORDEN DE LEER MENSAJES
    elif _PATRON_ULTIMO_MSJ.search(cmd_norm):
        hablar("Consultando el despacho de mensajes...")
        sms_raw = subprocess.check_output(['termux-sms-list', '-l', '1'])
        mensajes = json.loads(sms_raw)
        if mensajes:
            m = mensajes[0]
            hablar(f"El último mensaje es de {m['number']} y dice: {m['body']}")
        else:
            hablar("No hay mensajes nuevos en el registro.")

    # ORDEN DEEP 1
    elif _PATRON_DEEP1.search(cmd_norm):
        if deep_model_1 is None:
            hablar("El modelo Deep 1 no está disponible.")
            hablar("Verifica Modelos_IDS/deep_model_1.h5 e instalación de TensorFlow.")
        else:
            hablar("Modelo Deep 1 listo para inferencia.")
            # pred = deep_model_1.predict(datos_preprocesados)
            # hablar(f"Resultado Deep 1: {pred}")

    # ORDEN DEEP 2
    elif _PATRON_DEEP2.search(cmd_norm):
        if deep_model_2 is None:
            hablar("El modelo Deep 2 no está disponible.")
            hablar("Verifica Modelos_IDS/deep_model_2.pt e instalación de PyTorch.")
        else:
            hablar("Modelo Deep 2 listo para inferencia.")
            # with torch.no_grad():
            #     pred = deep_model_2(tensor_entrada)
            # hablar(f"Resultado Deep 2: {pred}")

    # ORDEN IDS (Random Forest). Se comprueba después de los "deep" para no
    # capturar por error un "ejecutar deep ..." con la palabra escaneo.
    elif _PATRON_ESCANEO.search(cmd_norm):
        if modelo is None:
            hablar("No puedo ejecutar el escaneo porque el modelo no está disponible.")
            hablar("Verifica que exista el archivo Modelos_IDS/ids_random_forest.pkl")
        else:
            hablar("Iniciando análisis con el modelo Random Forest...")
            # Aquí llamas a tu modelo con datos reales:
            # pred = modelo.predict(datos)
            # hablar(f"Resultado del escaneo: {pred}")

    # ORDEN OLLAMA
    elif ollama_match:
        prompt = ollama_match.group(1).strip().strip(".,;:!?¿¡").strip()
        if not prompt:
            hablar("Debes decirme qué preguntar a Ollama.")
        else:
            hablar("Consultando a Ollama...")
            respuesta_llm = consultar_ollama(prompt)
            hablar(respuesta_llm)

    elif "salir" in cmd_norm or "apagate" in cmd_norm:
        hablar("Cerrando Errik...")
        return False

    else:
        hablar('Comando no reconocido. Di "ayuda" para escuchar la lista de comandos.')

    return True

# INICIO DEL PROGRAMA (modo voz)
def main():
    hablar("Errik activado en modo voz. Di un comando.")
    while True:
        comando = escuchar_comando()
        if not comando:
            hablar("No entendí el comando, intenta de nuevo.")
            continue
        if not procesar(comando):
            break


if __name__ == "__main__":
    main()
