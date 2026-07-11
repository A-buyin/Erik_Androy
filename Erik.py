
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
import time
import shutil
import difflib
import unicodedata
import tempfile
import speech_recognition as sr
import joblib
import requests
import numpy as np
from pathlib import Path

# Extractor de features reales de la red (conexiones TCP del dispositivo).
try:
    from deteccion_red import extraer_features, NOMBRES_FEATURES
    DETECCION_RED_OK = True
except Exception as _e:
    extraer_features = None
    NOMBRES_FEATURES = []
    DETECCION_RED_OK = False
    print(f"Advertencia: no se pudo cargar deteccion_red: {_e}")

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

# Deep Learning 2 (PyTorch). Se reconstruye la red y se cargan los pesos.
deep_model_2 = None
deep_model_2_path = BASE_DIR / "Modelos_IDS" / "deep_model_2.pt"
try:
    import torch
    import torch.nn as nn

    # Misma arquitectura que en entrenar_modelos.py (10 features -> 2 clases).
    class TinyNet(nn.Module):
        def __init__(self, d):
            super().__init__()
            self.net = nn.Sequential(nn.Linear(d, 16), nn.ReLU(), nn.Linear(16, 2))

        def forward(self, x):
            return self.net(x)

    _estado = torch.load(deep_model_2_path, map_location="cpu")
    _d = len(NOMBRES_FEATURES) if NOMBRES_FEATURES else 10
    if isinstance(_estado, dict):
        deep_model_2 = TinyNet(_d)
        deep_model_2.load_state_dict(_estado)
        deep_model_2.eval()
    else:
        # Compatibilidad si el .pt guardara el modelo completo.
        deep_model_2 = _estado
        deep_model_2.eval()
    print(f"Modelo Deep 2 cargado correctamente desde: {deep_model_2_path}")
except FileNotFoundError:
    print(f"Advertencia: no se encontró el modelo Deep 2 en '{deep_model_2_path}'.")
except ImportError:
    print("Advertencia: PyTorch no está instalado. Deep 2 no disponible.")
except Exception as e:
    print(f"Advertencia al cargar Deep 2: {e}")

# Scaler compartido por los 3 modelos (StandardScaler ajustado en entrenamiento).
scaler = None
scaler_path = BASE_DIR / "Modelos_IDS" / "scaler.pkl"
try:
    scaler = joblib.load(scaler_path)
    print(f"Scaler cargado correctamente desde: {scaler_path}")
except FileNotFoundError:
    print(f"Advertencia: no se encontró el scaler en '{scaler_path}'. "
          f"Ejecuta 'python entrenar_modelos.py'.")
except Exception as e:
    print(f"Advertencia al cargar el scaler: {e}")

# 2. Configuración de Ollama (local o VPS remoto).
# NO se escriben aquí ni el dominio ni la clave: el repo es público. Se leen de
# variables de entorno o de errik_config.json (ignorado por git). Orden de
# prioridad: variables de entorno > errik_config.json > valores por defecto.
def _cargar_config_ollama():
    cfg = {
        "ollama_url": "http://localhost:11434/api/generate",
        "ollama_model": "llama3.1:8b",
        # Autenticación Basic (usuario/contraseña) — la que usa el proxy Caddy del VPS.
        "ollama_user": "",
        "ollama_password": "",
        # Alternativa: token Bearer (por si algún día se cambia el proxy).
        "ollama_api_key": "",
    }
    archivo = BASE_DIR / "errik_config.json"
    if archivo.exists():
        try:
            datos = json.loads(archivo.read_text(encoding="utf-8"))
            for clave in cfg:
                if datos.get(clave):
                    cfg[clave] = datos[clave]
        except Exception as e:
            print(f"Advertencia: errik_config.json inválido, se ignora: {e}")
    # Las variables de entorno tienen prioridad (útil para pruebas rápidas).
    cfg["ollama_url"] = os.environ.get("OLLAMA_URL", cfg["ollama_url"])
    cfg["ollama_model"] = os.environ.get("OLLAMA_MODEL", cfg["ollama_model"])
    cfg["ollama_user"] = os.environ.get("OLLAMA_USER", cfg["ollama_user"])
    cfg["ollama_password"] = os.environ.get("OLLAMA_PASSWORD", cfg["ollama_password"])
    cfg["ollama_api_key"] = os.environ.get("OLLAMA_API_KEY", cfg["ollama_api_key"])
    return cfg


_CFG_OLLAMA = _cargar_config_ollama()
OLLAMA_URL = _CFG_OLLAMA["ollama_url"]
OLLAMA_MODEL = _CFG_OLLAMA["ollama_model"]
OLLAMA_USER = _CFG_OLLAMA["ollama_user"]
OLLAMA_PASSWORD = _CFG_OLLAMA["ollama_password"]
OLLAMA_API_KEY = _CFG_OLLAMA["ollama_api_key"]

# Aviso discreto de a qué Ollama apunta (sin revelar credenciales).
_destino = "VPS remoto" if "localhost" not in OLLAMA_URL and "127.0.0.1" not in OLLAMA_URL else "local"
if OLLAMA_USER:
    _auth = "auth usuario/contraseña"
elif OLLAMA_API_KEY:
    _auth = "auth token"
else:
    _auth = "sin auth"
print(f"Ollama: modelo '{OLLAMA_MODEL}' en {_destino} ({_auth})")

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

[LLM Ollama]  (local o VPS remoto)
  • pregunta a ollama [texto]   -> consulta a Ollama
    variantes: "preguntale a ollama...", "consulta a ollama...", "o llama"
                                  URL/modelo/clave se configuran en errik_config.json
                                  (o variables OLLAMA_URL / OLLAMA_MODEL / OLLAMA_API_KEY)

[Sistema]
  • ayuda  /  help              -> muestra esta lista
  • clear  /  cls  /  limpiar   -> limpia la pantalla
  • salir  /  apagate           -> cierra Errik"""

# Personalidad de Erik + protocolo de acciones en JSON para que la app/Erik las
# ejecute. El modelo confirma en lenguaje natural y añade al final un bloque JSON.
SYSTEM_PROMPT_ERIK = """Eres Erik, el asistente personal de Ariel. Nunca digas que eres un modelo de lenguaje: eres Erik.
Tono amable, profesional y muy conciso. Dirígete siempre al usuario como "Ariel".
Responde en 1 o 2 frases, sin listas ni markdown, porque tu respuesta se lee en voz alta.

Cuando Ariel te pida una ACCIÓN que puedas ejecutar, confírmala en lenguaje natural y AÑADE AL FINAL,
en una línea aparte, un bloque JSON (un array) con la acción, para que la aplicación la ejecute.

Acciones disponibles y su formato EXACTO:
- Llamar a un contacto:  [{"accion":"llamar","contacto":"NOMBRE"}]
- Llamar a un número:    [{"accion":"llamar","numero":"3001234567"}]
- Colgar la llamada:     [{"accion":"colgar"}]
- Leer el último SMS:    [{"accion":"leer_mensaje"}]

Reglas del JSON:
- Incluye el bloque SOLO si Ariel pide una de esas acciones. Si es una pregunta normal, responde sin JSON.
- El JSON va SIEMPRE al final, en su propia línea, y nada después de él.
- Usa exactamente esos nombres de campo. No inventes otras acciones.

Ejemplos:
Ariel: "llama a Hilda"  ->  Claro, Ariel. Llamando a Hilda.
[{"accion":"llamar","contacto":"Hilda"}]

Ariel: "marca al 3005557788"  ->  Enseguida, Ariel. Marcando ese número.
[{"accion":"llamar","numero":"3005557788"}]

Ariel: "cuelga"  ->  Hecho, Ariel.
[{"accion":"colgar"}]

Si no comprendes la instrucción, di exactamente: "Lo siento Ariel, no comprendí la instrucción. ¿Podrías repetirla?" y no pongas JSON.
Trata la información de contactos con total confidencialidad. Solo ejecutas tareas; no expliques cómo funcionas."""


def consultar_ollama(prompt, system=None):
    try:
        payload = {
            "model": OLLAMA_MODEL,
            "prompt": prompt,
            "stream": False,
            # Mantener el modelo cargado en memoria 30 min para que no vuelva a
            # tardar ~90s en "arrancar en frío" en cada pregunta.
            "keep_alive": "30m",
            # Respuestas cortas: se leen en voz alta, no hace falta un ensayo.
            "options": {"num_predict": 200},
        }
        if system:
            payload["system"] = system
        headers = {}
        auth = None
        if OLLAMA_USER:
            # Basic auth (usuario/contraseña) — lo que exige el proxy Caddy del VPS.
            auth = (OLLAMA_USER, OLLAMA_PASSWORD)
        elif OLLAMA_API_KEY:
            headers["Authorization"] = f"Bearer {OLLAMA_API_KEY}"
        # timeout amplio: el primer arranque del modelo en el VPS puede tardar ~90s.
        respuesta = requests.post(OLLAMA_URL, json=payload, headers=headers,
                                  auth=auth, timeout=300)
        respuesta.raise_for_status()
        data = respuesta.json()
        return data.get("response", "").strip()
    except requests.exceptions.Timeout:
        return "El VPS de Ollama tardó demasiado en responder."
    except requests.exceptions.ConnectionError:
        return "No pude conectar con el VPS de Ollama. Verifica la URL y que esté encendido."
    except Exception as e:
        return f"No pude consultar Ollama. Detalle: {e}"


# Bloque JSON de acción que el modelo añade al final de su respuesta.
_PATRON_JSON_ACCION = re.compile(r"\[\s*\{.*?\}\s*\]", re.DOTALL)


def extraer_acciones(respuesta):
    """Separa la respuesta del modelo en (texto_para_voz, lista_de_acciones).

    El modelo, según SYSTEM_PROMPT_ERIK, puede terminar con un array JSON como
    [{"accion":"llamar","contacto":"Hilda"}]. Lo extraemos y lo quitamos del
    texto que se lee en voz alta.
    """
    if not respuesta:
        return "", []
    acciones = []
    texto = respuesta
    m = _PATRON_JSON_ACCION.search(respuesta)
    if m:
        bloque = m.group(0)
        try:
            data = json.loads(bloque)
            if isinstance(data, list):
                acciones = [a for a in data if isinstance(a, dict) and a.get("accion")]
        except Exception:
            acciones = []
        if acciones:
            texto = respuesta.replace(bloque, "").strip()
    return texto, acciones


def ejecutar_accion(accion):
    """Ejecuta una acción devuelta por el modelo. Devuelve True si hizo algo."""
    tipo = (accion.get("accion") or "").lower().strip()

    if tipo == "llamar":
        numero = (accion.get("numero") or "").strip()
        contacto = (accion.get("contacto") or "").strip()
        if numero and es_numero_telefono(numero):
            num = re.sub(r"[\s\-()\.]", "", numero)
            hablar(f"Marcando al número {num}, Ariel.")
            subprocess.run(['termux-telephony-call', num])
            return True
        if contacto:
            encontrado = buscar_contacto(contacto)
            if encontrado:
                nombre, num = encontrado
                hablar(f"Llamando a {nombre}, Ariel.")
                subprocess.run(['termux-telephony-call', num])
            else:
                hablar(f"No encontré a {contacto} en tus contactos, Ariel.")
            return True
        hablar("No entendí a quién llamar, Ariel.")
        return True

    if tipo == "colgar":
        # termux-api no expone un comando fiable para colgar; se informa con honestidad.
        hablar("Colgar la llamada no está disponible en esta versión de Termux, Ariel.")
        return True

    if tipo in ("leer_mensaje", "leer_sms", "ultimo_mensaje"):
        leer_ultimo_mensaje()
        return True

    return False


def interpretar_y_ejecutar(texto):
    """Cerebro de Erik: manda el texto al modelo con la personalidad Erik,
    locuta su respuesta y ejecuta las acciones JSON que devuelva."""
    respuesta = consultar_ollama(texto, system=SYSTEM_PROMPT_ERIK)
    texto_voz, acciones = extraer_acciones(respuesta)
    if texto_voz:
        hablar(texto_voz)
    for accion in acciones:
        try:
            ejecutar_accion(accion)
        except Exception as e:
            print(f"Advertencia al ejecutar acción {accion}: {e}")


def leer_ultimo_mensaje():
    """Lee por voz el último SMS recibido (requiere Termux)."""
    try:
        sms_raw = subprocess.check_output(['termux-sms-list', '-l', '1'])
        mensajes = json.loads(sms_raw)
    except Exception:
        hablar("No pude acceder a los mensajes, Ariel.")
        return
    if mensajes:
        m = mensajes[0]
        hablar(f"El último mensaje es de {m.get('number','desconocido')} y dice: {m.get('body','')}")
    else:
        hablar("No hay mensajes nuevos en el registro, Ariel.")

# Detección tolerante de la orden de llamada.
# Acepta: llama / llamar / llamada / marca / marcar / telefonea(r),
# con o sin "a"/"al" (útil cuando Whisper se come la "a": "llama Juan").
_PATRON_LLAMADA = re.compile(
    # llam* cubre llama/llamar/llamada/llamando; marc* cubre marca/marcar/marcando.
    r"^\s*(?:llam\w*|lama|yama|marc\w*|telefone\w*)\s+(?:al?\s+)?(.+)$",
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


def es_numero_telefono(texto):
    """True si el objetivo parece un número marcable (solo dígitos y símbolos
    típicos: +, -, espacios, paréntesis). Así distinguimos "300123456" de
    un nombre de contacto como "Juan"."""
    solo = re.sub(r"[\s\-()\.]", "", texto or "")
    return bool(re.fullmatch(r"\+?\d{3,}", solo))


def obtener_contactos():
    """Lee la agenda de Android con termux-contact-list y devuelve una lista de
    dicts {name, number}. Devuelve [] si no estamos en Termux o si falla
    (p. ej. sin permiso de contactos o sin la app Termux:API)."""
    if not shutil.which("termux-contact-list"):
        return []
    try:
        raw = subprocess.check_output(
            ["termux-contact-list"], stderr=subprocess.DEVNULL
        )
        datos = json.loads(raw)
        return [c for c in datos if c.get("name") and c.get("number")]
    except Exception:
        return []


def buscar_contacto(objetivo, contactos=None, umbral=0.62):
    """Empareja lo que dijo el usuario ('objetivo') con el contacto más
    parecido de la agenda, tolerando acentos y errores de transcripción de
    Whisper. Devuelve (nombre, numero) del mejor contacto o None si no hay
    ninguno lo bastante parecido.

    Estrategia de puntuación (sobre texto normalizado):
      • similitud del nombre completo (difflib),
      • coincidencia con cada palabra suelta del nombre  ("juan" -> "Juan Pérez"),
      • el objetivo contenido en el nombre o viceversa.
    """
    if contactos is None:
        contactos = obtener_contactos()
    if not contactos:
        return None

    obj = normalizar(objetivo)
    if not obj:
        return None

    mejor = None
    mejor_score = 0.0
    for c in contactos:
        nombre = c.get("name", "")
        numero = c.get("number", "")
        nombre_norm = normalizar(nombre)
        if not nombre_norm or not numero:
            continue

        # Similitud del nombre completo.
        score = difflib.SequenceMatcher(None, obj, nombre_norm).ratio()

        # Coincidencia por palabras sueltas (nombre de pila o apellido).
        for parte in nombre_norm.split():
            if parte == obj:
                score = max(score, 0.98)
            else:
                score = max(score, difflib.SequenceMatcher(None, obj, parte).ratio() * 0.85)

        # El objetivo aparece dentro del nombre (o al revés).
        if obj in nombre_norm or nombre_norm in obj:
            score = max(score, 0.9)

        if score > mejor_score:
            mejor_score = score
            mejor = (nombre, numero)

    if mejor and mejor_score >= umbral:
        return mejor
    return None


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
# Saludo de activación: "hola Erik", "oye Erik", o solo "Erik".
_PATRON_SALUDO = re.compile(r"^\s*(?:(?:hola|ola|oye|hey|ey)\s+)?(?:erik|eric|erick|herik)\s*$")
# Colgar la llamada.
_PATRON_COLGAR = re.compile(r"\bcuelg\w*\b|\bcolg\w*\b|\bcort\w*\s+la\s+llamad\w*")


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


def _leer_red_para_modelo():
    """Extrae las features reales de la red y las deja listas para los modelos.

    Devuelve (X_escalado_2d, vector_crudo, legible) o (None, None, None) si no se
    pudo leer la red. `hablar` ya informa del motivo.
    """
    if not DETECCION_RED_OK or extraer_features is None:
        hablar("No tengo el módulo de detección de red disponible.")
        return None, None, None
    vector, legible = extraer_features()
    if vector is None:
        hablar("No pude leer las conexiones de red en este dispositivo.")
        hablar("En Android con Termux debería funcionar; en el PC puede faltar.")
        return None, None, None
    X = vector.reshape(1, -1).astype("float32")
    if scaler is not None:
        try:
            X = scaler.transform(X).astype("float32")
        except Exception as e:
            print(f"Advertencia: fallo al escalar features: {e}")
    return X, vector, legible


def _anunciar_veredicto(nombre_modelo, clase, prob_anomalo, legible):
    """Locuta el resultado de una predicción de forma uniforme."""
    estado = "ANÓMALA" if clase == 1 else "NORMAL"
    pct = int(round(prob_anomalo * 100))
    hablar(f"{nombre_modelo}: la actividad de red parece {estado}. "
           f"Probabilidad de anomalía {pct} por ciento.")
    if legible:
        # Contexto breve de las señales más relevantes.
        hablar(f"Conexiones activas {int(legible['total_conexiones'])}, "
               f"IPs remotas distintas {int(legible['ips_remotas_unicas'])}, "
               f"intentos salientes {int(legible['syn_sent'])}.")


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


def _termux_microfono_disponible():
    """True si estamos en Android/Termux con la herramienta de grabación."""
    return shutil.which("termux-microphone-record") is not None


def grabar_audio_termux(segundos=6):
    """Graba audio en Android con termux-microphone-record y devuelve la ruta
    a un WAV 16 kHz mono listo para transcribir. PyAudio/sr.Microphone no
    funciona dentro de Termux, por eso en el teléfono se usa esta vía.

    Requiere la app Termux:API y el paquete ffmpeg. Devuelve None si falla.
    """
    m4a_path = None
    wav_path = None
    try:
        with tempfile.NamedTemporaryFile(suffix=".m4a", delete=False) as tmp:
            m4a_path = tmp.name

        # Inicia la grabación; con -l se detiene sola tras 'segundos'.
        subprocess.run(
            ["termux-microphone-record", "-f", m4a_path, "-l", str(segundos)],
            check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        # Espera a que termine y cierra la grabación por si sigue activa.
        time.sleep(segundos + 0.3)
        subprocess.run(
            ["termux-microphone-record", "-q"],
            check=False, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )

        # Convierte a WAV 16 kHz mono (formato que aceptan Whisper y Google STT).
        with tempfile.NamedTemporaryFile(suffix=".wav", delete=False) as tmp:
            wav_path = tmp.name
        subprocess.run(
            ["ffmpeg", "-y", "-i", m4a_path, "-ar", "16000", "-ac", "1", wav_path],
            check=True, stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL,
        )
        return wav_path
    except Exception:
        if wav_path and os.path.exists(wav_path):
            try:
                os.remove(wav_path)
            except Exception:
                pass
        return None
    finally:
        if m4a_path and os.path.exists(m4a_path):
            try:
                os.remove(m4a_path)
            except Exception:
                pass


def _audio_desde_wav(wav_path):
    """Carga un WAV como AudioData de SpeechRecognition para reutilizar el
    mismo pipeline de transcripción (Whisper local o Google STT)."""
    reconocedor = sr.Recognizer()
    with sr.AudioFile(wav_path) as source:
        return reconocedor.record(source)


def escuchar_comando():
    # En Android (Termux) el micrófono se captura con termux-microphone-record;
    # en escritorio (Windows/Linux) se usa PyAudio vía sr.Microphone.
    if _termux_microfono_disponible():
        print("Errik está escuchando...")
        wav_path = grabar_audio_termux(segundos=6)
        if not wav_path:
            return ""
        try:
            audio = _audio_desde_wav(wav_path)
        except Exception:
            return ""
        finally:
            if os.path.exists(wav_path):
                try:
                    os.remove(wav_path)
                except Exception:
                    pass
        comando = transcribir_audio(audio)
        if comando:
            print(f"Dijiste: {comando}")
            return comando.lower()
        return ""

    # Escritorio: micrófono con PyAudio.
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

    # SALUDO DE ACTIVACIÓN ("hola Erik"). El bucle principal ya sigue escuchando.
    if _PATRON_SALUDO.match(cmd_norm):
        hablar("Hola Ariel, ¿en qué te puedo ayudar?")
        return True

    # COLGAR. En Termux/Android no hay API para colgar; se informa con honestidad.
    if _PATRON_COLGAR.search(cmd_norm):
        hablar("Colgar la llamada no está disponible en la versión de Termux, Ariel.")
        return True

    ollama_match = _PATRON_OLLAMA.search(cmd_norm)

    # ORDEN DE LLAMADA
    objetivo_llamada = extraer_objetivo_llamada(cmd)
    if objetivo_llamada:
        if es_numero_telefono(objetivo_llamada):
            # Se dijo un número: marcar directamente.
            numero = re.sub(r"[\s\-()\.]", "", objetivo_llamada)
            hablar(f"Entendido. Marcando al número {numero}")
            subprocess.run(['termux-telephony-call', numero])
        else:
            # Se dijo un nombre: buscarlo en la agenda del teléfono.
            contacto = buscar_contacto(objetivo_llamada)
            if contacto:
                nombre, numero = contacto
                hablar(f"Entendido. Llamando a {nombre}")
                subprocess.run(['termux-telephony-call', numero])
            else:
                hablar(f"No encontré a {objetivo_llamada} en tus contactos.")

    # ORDEN DE LEER MENSAJES
    elif _PATRON_ULTIMO_MSJ.search(cmd_norm):
        hablar("Consultando el despacho de mensajes...")
        leer_ultimo_mensaje()

    # ORDEN DEEP 1 (Keras/TensorFlow) sobre la red real
    elif _PATRON_DEEP1.search(cmd_norm):
        if deep_model_1 is None:
            hablar("El modelo Deep 1 no está disponible.")
            hablar("Verifica Modelos_IDS/deep_model_1.h5 e instalación de TensorFlow.")
        else:
            hablar("Analizando la red con la red neuronal Keras...")
            X, _vector, legible = _leer_red_para_modelo()
            if X is not None:
                probs = deep_model_1.predict(X, verbose=0)[0]
                clase = int(np.argmax(probs))
                _anunciar_veredicto("Deep uno", clase, float(probs[1]), legible)

    # ORDEN DEEP 2 (PyTorch) sobre la red real
    elif _PATRON_DEEP2.search(cmd_norm):
        if deep_model_2 is None:
            hablar("El modelo Deep 2 no está disponible.")
            hablar("Verifica Modelos_IDS/deep_model_2.pt e instalación de PyTorch.")
        else:
            hablar("Analizando la red con la red neuronal PyTorch...")
            X, _vector, legible = _leer_red_para_modelo()
            if X is not None:
                import torch
                with torch.no_grad():
                    logits = deep_model_2(torch.from_numpy(X))
                    probs = torch.softmax(logits, dim=1)[0].numpy()
                clase = int(np.argmax(probs))
                _anunciar_veredicto("Deep dos", clase, float(probs[1]), legible)

    # ORDEN IDS (Random Forest). Se comprueba después de los "deep" para no
    # capturar por error un "ejecutar deep ..." con la palabra escaneo.
    elif _PATRON_ESCANEO.search(cmd_norm):
        if modelo is None:
            hablar("No puedo ejecutar el escaneo porque el modelo no está disponible.")
            hablar("Verifica que exista el archivo Modelos_IDS/ids_random_forest.pkl")
        else:
            hablar("Iniciando análisis de la red con el modelo Random Forest...")
            X, _vector, legible = _leer_red_para_modelo()
            if X is not None:
                clase = int(modelo.predict(X)[0])
                try:
                    prob_anomalo = float(modelo.predict_proba(X)[0][1])
                except Exception:
                    prob_anomalo = float(clase)
                _anunciar_veredicto("Random Forest", clase, prob_anomalo, legible)

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
        # Todo lo que no sea un comando específico se lo pasamos al modelo del VPS:
        # Erik interpreta la intención con IA y ejecuta las acciones (llamar, etc.)
        # o responde la pregunta. Ollama es la herramienta-cerebro fija de Erik.
        texto = comando.strip()
        if len(texto) >= 3:
            hablar("Un momento, Ariel...")
            interpretar_y_ejecutar(texto)
        else:
            hablar('No te entendí, Ariel. Di "ayuda" para escuchar la lista de comandos.')

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
