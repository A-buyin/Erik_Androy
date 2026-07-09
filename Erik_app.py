import os
os.environ["TF_CPP_MIN_LOG_LEVEL"] = "3"
os.environ["TF_ENABLE_ONEDNN_OPTS"] = "0"
os.environ["GRPC_VERBOSITY"] = "ERROR"
os.environ["GLOG_minloglevel"] = "3"

import logging
logging.getLogger("tensorflow").setLevel(logging.ERROR)
logging.getLogger("absl").setLevel(logging.ERROR)

import tkinter as tk
from tkinter import ttk
from tkinter.scrolledtext import ScrolledText
from pathlib import Path
import threading
import subprocess
import json
import re
import unicodedata
import shlex
import speech_recognition as sr
import joblib
import requests
import warnings
import tempfile
warnings.filterwarnings("ignore", category=UserWarning)

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

# =========================
# Configuración base
# =========================
BASE_DIR = Path(__file__).resolve().parent
LOGO_PATH = BASE_DIR / "logo" / "errik_logo_transparente.png"

# IDS
modelo = None
modelo_path = BASE_DIR / "Modelos_IDS" / "ids_random_forest.pkl"
try:
    modelo = joblib.load(modelo_path)
    IDS_STATUS = f"Modelo IDS cargado: {modelo_path}"
except FileNotFoundError:
    IDS_STATUS = f"Modelo IDS no encontrado: {modelo_path}"
except Exception as e:
    IDS_STATUS = f"Error cargando modelo IDS: {e}"

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
    DEEP1_STATUS = f"Modelo Deep 1 cargado: {deep_model_1_path}"
except FileNotFoundError:
    DEEP1_STATUS = f"Modelo Deep 1 no encontrado: {deep_model_1_path}"
except ImportError:
    DEEP1_STATUS = "TensorFlow no instalado. Deep 1 no disponible."
except Exception as e:
    DEEP1_STATUS = f"Error cargando Deep 1: {e}"

# Deep Learning 2 (PyTorch placeholder)
deep_model_2 = None
deep_model_2_path = BASE_DIR / "Modelos_IDS" / "deep_model_2.pt"
try:
    import torch
    deep_model_2 = torch.load(deep_model_2_path, map_location="cpu")
    DEEP2_STATUS = f"Modelo Deep 2 cargado: {deep_model_2_path}"
except FileNotFoundError:
    DEEP2_STATUS = f"Modelo Deep 2 no encontrado: {deep_model_2_path}"
except ImportError:
    DEEP2_STATUS = "PyTorch no instalado. Deep 2 no disponible."
except Exception as e:
    DEEP2_STATUS = f"Error cargando Deep 2: {e}"

# Ollama
OLLAMA_URL = "http://localhost:11434/api/generate"
OLLAMA_MODEL = "llama3.1:8b"

TEXTO_AYUDA = """COMANDOS DISPONIBLES

(Los comandos toleran acentos, mayúsculas y errores típicos de la voz.)

[Telefonía / SMS]  (requiere Termux en Android)
  • llama a [número/contacto]   → marca vía termux-telephony-call
    variantes: llama / llamar / llamada / marca / marcar / telefonea
    con o sin "a"/"al"  (ej: "llama Juan", "marca 300123", "llama al 300...")
  • ultimo mensaje              → lee el último SMS recibido
    variantes: "último mensaje", "ultimo sms", "lee el ultimo mensaje"

[Modelos IDS / Machine Learning]
  • ejecutar escaneo            → Random Forest  (scikit-learn)
    variantes: "ejecuta escáner", "ejecutar escanear", "ejecuta el escaneo"
                                  Archivo: Modelos_IDS/ids_random_forest.pkl
  • ejecutar deep uno           → Red neuronal   (Keras / TensorFlow)
    variantes: deep/dip/dep/the + uno/1/primero  (ej: "ejecuta dip uno")
                                  Archivo: Modelos_IDS/deep_model_1.h5
  • ejecutar deep dos           → Red neuronal   (PyTorch)
    variantes: deep/dip/dep/the + dos/2/segundo  (ej: "ejecuta dip 2")
                                  Archivo: Modelos_IDS/deep_model_2.pt

[LLM local]
  • pregunta a ollama [texto]   → consulta a Ollama (modelo llama3.1:8b)
    variantes: "pregúntale a ollama...", "consulta a ollama...", "o llama"
                                  Requiere Ollama corriendo en localhost:11434

[Documentos]
  • editar documento word [ruta] → abre .doc/.docx con la app predeterminada
  • imprimir documento [ruta]    → envía el documento a la impresora predeterminada

[Terminal]
  • ejecuta terminal [comando]  → ejecuta en PowerShell/CMD
                                  Bloqueados: format, shutdown, del /f, etc.

[Sistema]
  • ayuda  /  help              → muestra esta lista
  • clear  /  cls  /  limpiar   → limpia el área de conversación
  • salir  /  apágate           → cierra Errik"""


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


def consultar_ollama(prompt):
    try:
        payload = {"model": OLLAMA_MODEL, "prompt": prompt, "stream": False}
        respuesta = requests.post(OLLAMA_URL, json=payload, timeout=60)
        respuesta.raise_for_status()
        data = respuesta.json()
        return data.get("response", "").strip() or "Ollama no devolvió texto."
    except Exception as e:
        return f"No pude consultar Ollama. Verifica que esté corriendo. Detalle: {e}"


def termux_disponible():
    try:
        import shutil
        return shutil.which("termux-tts-speak") is not None
    except Exception:
        return False


def escuchar_audio():
    reconocedor = sr.Recognizer()
    with sr.Microphone() as source:
        reconocedor.adjust_for_ambient_noise(source, duration=0.5)
        audio = reconocedor.listen(source, timeout=5, phrase_time_limit=10)
    return audio


def transcribir_con_whisper(audio_data):
    if not WHISPER_AVAILABLE:
        return ""

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
            if "temp_path" in locals() and os.path.exists(temp_path):
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


def comando_peligroso(comando: str) -> bool:
    bloqueados = [
        "format",
        "shutdown",
        "restart-computer",
        "stop-computer",
        "del /f",
        "rd /s",
        "rmdir /s",
        "cipher /w",
        "diskpart",
        "reg delete",
        "bcdedit",
        "takeown",
        "icacls /reset",
    ]
    c = comando.lower().strip()
    return any(p in c for p in bloqueados)


def ejecutar_en_terminal(comando: str):
    try:
        proceso = subprocess.run(
            comando,
            shell=True,
            capture_output=True,
            text=True,
            encoding="utf-8",
            errors="replace",
            timeout=60,
        )
        salida = (proceso.stdout or "").strip()
        error = (proceso.stderr or "").strip()
        codigo = proceso.returncode
        return codigo, salida, error
    except Exception as e:
        return 1, "", f"Error ejecutando comando: {e}"


def _extraer_ruta_entre_comillas_o_texto(valor: str) -> str:
    valor = (valor or "").strip()
    if not valor:
        return ""

    try:
        partes = shlex.split(valor, posix=False)
        if partes:
            return partes[0].strip().strip('"').strip("'")
    except Exception:
        pass

    return valor.strip().strip('"').strip("'")


def _normalizar_ruta_documento(ruta_ingresada: str) -> Path:
    ruta_txt = _extraer_ruta_entre_comillas_o_texto(ruta_ingresada)
    p = Path(ruta_txt)
    if not p.is_absolute():
        p = BASE_DIR / p
    return p.resolve()


def abrir_documento_word(ruta_ingresada: str):
    try:
        ruta = _normalizar_ruta_documento(ruta_ingresada)
        if not ruta.exists():
            return False, f"No encontré el archivo: {ruta}"
        if ruta.suffix.lower() not in (".doc", ".docx"):
            return False, "Solo se permiten archivos .doc o .docx para editar en Word."

        os.startfile(str(ruta))  # type: ignore[attr-defined]
        return True, f"Abriendo documento en Word (o app asociada): {ruta}"
    except Exception as e:
        return False, f"No pude abrir el documento. Detalle: {e}"


def imprimir_documento(ruta_ingresada: str):
    try:
        ruta = _normalizar_ruta_documento(ruta_ingresada)
        if not ruta.exists():
            return False, f"No encontré el archivo: {ruta}"
        if ruta.suffix.lower() not in (".doc", ".docx"):
            return False, "Solo se permiten archivos .doc o .docx para imprimir."

        os.startfile(str(ruta), "print")  # type: ignore[attr-defined]
        return True, f"Enviando a impresión: {ruta}"
    except Exception as e:
        return False, f"No pude imprimir el documento. Detalle: {e}"


class ErrikApp:
    def __init__(self, root):
        self.root = root
        self.root.title("Errik - Asistente")
        self.root.geometry("700x560")
        self.root.minsize(620, 500)

        self.main = ttk.Frame(root, padding=10)
        self.main.pack(fill="both", expand=True)

        self._build_ui()
        self._append("Errik", 'App iniciada. Escribe "ayuda" o pulsa el botón Ayuda para ver los comandos.')
        self._append("Sistema", IDS_STATUS)
        self._append("Sistema", DEEP1_STATUS)
        self._append("Sistema", DEEP2_STATUS)

    def _build_ui(self):
        top = ttk.Frame(self.main)
        top.pack(fill="x", pady=(0, 10))

        # Logo
        self.logo_label = ttk.Label(top)
        self.logo_label.pack(side="left", padx=(0, 12))
        self.logo_img = None
        self._load_logo()

        # Estado y controles
        controls = ttk.Frame(top)
        controls.pack(side="left", fill="x", expand=True)

        ttk.Label(
            controls,
            text="Errik - Control por voz + texto",
            font=("Segoe UI", 12, "bold"),
        ).pack(anchor="w")

        self.status_var = tk.StringVar(value="Listo")
        ttk.Label(controls, textvariable=self.status_var).pack(anchor="w", pady=(4, 8))

        btn_row = ttk.Frame(controls)
        btn_row.pack(anchor="w")

        self.listen_btn = ttk.Button(btn_row, text="🎤 Escuchar", command=self._on_escuchar)
        self.listen_btn.pack(side="left", padx=(0, 8))

        self.command_btn = ttk.Button(btn_row, text="🎤 Comando", command=self._on_comando_voz)
        self.command_btn.pack(side="left", padx=(0, 8))

        self.help_btn = ttk.Button(btn_row, text="Ayuda", command=self._on_ayuda)
        self.help_btn.pack(side="left", padx=(0, 8))

        self.clear_btn = ttk.Button(btn_row, text="Limpiar", command=self._on_clear)
        self.clear_btn.pack(side="left", padx=(0, 8))

        self.exit_btn = ttk.Button(btn_row, text="Salir", command=self.root.destroy)
        self.exit_btn.pack(side="left")

        # Conversación
        self.chat = ScrolledText(self.main, wrap="word", height=20, state="disabled")
        self.chat.pack(fill="both", expand=True, pady=(0, 10))

        # Entrada manual
        entry_row = ttk.Frame(self.main)
        entry_row.pack(fill="x")

        self.entry = ttk.Entry(entry_row)
        self.entry.pack(side="left", fill="x", expand=True, padx=(0, 8))
        self.entry.bind("<Return>", lambda event: self._on_enviar())

        self.send_btn = ttk.Button(entry_row, text="Enviar", command=self._on_enviar)
        self.send_btn.pack(side="left")

    def _load_logo(self):
        if LOGO_PATH.exists():
            try:
                self.logo_img = tk.PhotoImage(file=str(LOGO_PATH))
                self.logo_label.configure(image=self.logo_img)
                return
            except Exception:
                pass
        self.logo_label.configure(text="[Logo no disponible]")

    def _append(self, autor, texto):
        self.chat.configure(state="normal")
        self.chat.insert("end", f"{autor}: {texto}\n")
        self.chat.see("end")
        self.chat.configure(state="disabled")

    def hablar(self, texto):
        self._append("Errik", texto)
        if termux_disponible():
            try:
                subprocess.run(["termux-tts-speak", texto], check=False)
            except Exception:
                pass

    def _mostrar_ayuda(self):
        self._append("Errik", TEXTO_AYUDA)

    def _on_ayuda(self):
        self._mostrar_ayuda()

    def _limpiar_pantalla(self):
        self.chat.configure(state="normal")
        self.chat.delete("1.0", "end")
        self.chat.configure(state="disabled")

    def _on_clear(self):
        self._limpiar_pantalla()

    def procesar(self, comando):
        cmd = comando.lower().strip()
        cmd_norm = normalizar(cmd)  # sin acentos/mayúsculas, para tolerar la voz

        if cmd in ("ayuda", "help", "?") or cmd.startswith("ayuda ") or cmd.startswith("help "):
            self._mostrar_ayuda()
            return

        if cmd in ("clear", "cls", "limpiar", "limpia"):
            self._limpiar_pantalla()
            return

        ollama_match = _PATRON_OLLAMA.search(cmd_norm)

        objetivo_llamada = extraer_objetivo_llamada(cmd)
        if objetivo_llamada:
            self.hablar(f"Entendido. Marcando a {objetivo_llamada}")
            try:
                subprocess.run(["termux-telephony-call", objetivo_llamada], check=False)
            except Exception as e:
                self.hablar(f"No pude realizar la llamada en este entorno. Detalle: {e}")

        elif _PATRON_ULTIMO_MSJ.search(cmd_norm):
            self.hablar("Consultando el despacho de mensajes...")
            try:
                sms_raw = subprocess.check_output(["termux-sms-list", "-l", "1"])
                mensajes = json.loads(sms_raw)
                if mensajes:
                    m = mensajes[0]
                    self.hablar(f"El último mensaje es de {m['number']} y dice: {m['body']}")
                else:
                    self.hablar("No hay mensajes nuevos en el registro.")
            except Exception as e:
                self.hablar(f"No pude leer mensajes en este entorno. Detalle: {e}")

        elif _PATRON_DEEP1.search(cmd_norm):
            if deep_model_1 is None:
                self.hablar("El modelo Deep 1 no está disponible.")
                self.hablar("Verifica Modelos_IDS/deep_model_1.h5 e instalación de TensorFlow.")
            else:
                self.hablar("Modelo Deep 1 listo para inferencia.")
                # pred = deep_model_1.predict(datos_preprocesados)
                # self.hablar(f"Resultado Deep 1: {pred}")

        elif _PATRON_DEEP2.search(cmd_norm):
            if deep_model_2 is None:
                self.hablar("El modelo Deep 2 no está disponible.")
                self.hablar("Verifica Modelos_IDS/deep_model_2.pt e instalación de PyTorch.")
            else:
                self.hablar("Modelo Deep 2 listo para inferencia.")
                # with torch.no_grad():
                #     pred = deep_model_2(tensor_entrada)
                # self.hablar(f"Resultado Deep 2: {pred}")

        # IDS (Random Forest): después de los "deep" para no capturar por error.
        elif _PATRON_ESCANEO.search(cmd_norm):
            if modelo is None:
                self.hablar("No puedo ejecutar el escaneo porque el modelo no está disponible.")
                self.hablar("Verifica que exista el archivo Modelos_IDS/ids_random_forest.pkl")
            else:
                self.hablar("Iniciando análisis con el modelo Random Forest...")
                # pred = modelo.predict(datos)
                # self.hablar(f"Resultado del escaneo: {pred}")

        elif ollama_match:
            prompt = ollama_match.group(1).strip().strip(".,;:!?¿¡").strip()
            if not prompt:
                self.hablar("Debes decirme qué preguntar a Ollama.")
            else:
                self.hablar("Consultando a Ollama...")
                respuesta_llm = consultar_ollama(prompt)
                self.hablar(respuesta_llm)

        elif cmd.startswith("editar documento word "):
            ruta_doc = comando[len("editar documento word "):].strip()
            if not ruta_doc:
                self.hablar('Debes indicar la ruta. Ejemplo: editar documento word "C:\\\\Docs\\\\archivo.docx"')
            else:
                ok, msg = abrir_documento_word(ruta_doc)
                self.hablar(msg if ok else f"Error: {msg}")

        elif cmd.startswith("imprimir documento "):
            ruta_doc = comando[len("imprimir documento "):].strip()
            if not ruta_doc:
                self.hablar('Debes indicar la ruta. Ejemplo: imprimir documento "C:\\\\Docs\\\\archivo.docx"')
            else:
                ok, msg = imprimir_documento(ruta_doc)
                self.hablar(msg if ok else f"Error: {msg}")

        elif cmd.startswith("ejecuta terminal "):
            comando_terminal = comando[len("ejecuta terminal "):].strip()
            if not comando_terminal:
                self.hablar("Debes indicar un comando. Ejemplo: ejecuta terminal dir")
            elif comando_peligroso(comando_terminal):
                self.hablar("Ese comando está bloqueado por seguridad.")
            else:
                self.hablar(f"Ejecutando en terminal: {comando_terminal}")
                codigo, salida, error = ejecutar_en_terminal(comando_terminal)
                self._append("Terminal", f"Código de salida: {codigo}")
                if salida:
                    self._append("Terminal", f"Salida:\n{salida}")
                if error:
                    self._append("Terminal", f"Error:\n{error}")

        elif "salir" in cmd_norm or "apagate" in cmd_norm:
            self.hablar("Cerrando Errik...")
            self.root.after(200, self.root.destroy)

        else:
            self.hablar('Comando no reconocido. Escribe "ayuda" para ver la lista de comandos.')

    def _on_enviar(self):
        comando = self.entry.get().strip()
        if not comando:
            return
        self.entry.delete(0, "end")
        self._append("Tú", comando)
        self.procesar(comando)

    def _insertar_dictado_en_entry(self, texto_dictado: str):
        texto_actual = self.entry.get()
        if texto_actual and not texto_actual.endswith((" ", "\n", "\t")):
            self.entry.insert("end", " ")
        self.entry.insert("end", texto_dictado)
        self.entry.focus_set()
        self.entry.icursor("end")

    def _set_mic_botones(self, estado):
        self.listen_btn.configure(state=estado)
        self.command_btn.configure(state=estado)

    def _escuchar_worker(self, ejecutar):
        self.root.after(0, lambda: self.status_var.set("Escuchando..."))
        self.root.after(0, lambda: self._set_mic_botones("disabled"))

        audio = None
        try:
            audio = escuchar_audio()
        except Exception:
            audio = None

        if audio is None:
            self.root.after(0, lambda: self.hablar("No pude acceder al micrófono."))
            self.root.after(0, lambda: self.status_var.set("Listo"))
            self.root.after(0, lambda: self._set_mic_botones("normal"))
            return

        self.root.after(0, lambda: self.status_var.set("Transcribiendo..."))
        texto = transcribir_audio(audio)

        if texto:
            if ejecutar:
                self.root.after(0, lambda t=texto: self._append("Tú (voz)", t))
                self.root.after(0, lambda t=texto: self.procesar(t))
            else:
                self.root.after(0, lambda t=texto: self._append("Tú (dictado)", t))
                self.root.after(0, lambda t=texto: self._insertar_dictado_en_entry(t))
        else:
            self.root.after(0, lambda: self.hablar("No pude transcribir el audio, intenta de nuevo."))

        self.root.after(0, lambda: self.status_var.set("Listo"))
        self.root.after(0, lambda: self._set_mic_botones("normal"))

    def _on_escuchar(self):
        threading.Thread(target=self._escuchar_worker, args=(False,), daemon=True).start()

    def _on_comando_voz(self):
        threading.Thread(target=self._escuchar_worker, args=(True,), daemon=True).start()


if __name__ == "__main__":
    root = tk.Tk()
    style = ttk.Style()
    try:
        style.theme_use("clam")
    except Exception:
        pass
    app = ErrikApp(root)
    root.mainloop()
