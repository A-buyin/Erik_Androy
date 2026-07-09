# Errik 🎙️

**Asistente personal por voz para Android (Termux), en español.**

Errik escucha comandos de voz, los transcribe con Whisper (con respaldo en Google STT) y ejecuta acciones en el teléfono: llamadas, lectura de SMS, consultas a un modelo de lenguaje local (Ollama) y ejecución de modelos de Machine Learning para detección de intrusos (IDS).

> **Plataforma destino:** Android + [Termux](https://termux.dev). Windows se usa solo como entorno de desarrollo.

---

## ✨ Características

- **Reconocimiento de voz en español** — Whisper local (`faster-whisper`, modelo `small`) con respaldo automático a Google STT si no hay Whisper disponible.
- **Emparejador tolerante de comandos** — entiende acentos, mayúsculas y errores típicos de transcripción (p. ej. `llama Juan`, `marca 300...`, `ejecuta dip uno`).
- **Telefonía y SMS** (vía Termux): hacer llamadas y leer el último mensaje recibido.
- **LLM local con Ollama** — preguntas en lenguaje natural al modelo `llama3.1:8b`.
- **Modelos IDS / ML** — Random Forest (scikit-learn), red neuronal Keras/TensorFlow y red PyTorch (actualmente *placeholders* listos para conectar datos reales).
- **Dos interfaces**:
  - `Erik.py` — modo voz por consola.
  - `Erik_app.py` — aplicación de escritorio con interfaz gráfica (Tkinter), botón *Escuchar*, terminal integrada y utilidades de documentos.

---

## 🗣️ Comandos disponibles

Los comandos toleran acentos, mayúsculas y errores típicos de la voz.

| Comando | Acción | Variantes |
|---|---|---|
| `llama a [número/contacto]` | Marca vía `termux-telephony-call` | llama / llamar / llamada / marca / marcar / telefonea (con o sin "a"/"al") |
| `ultimo mensaje` | Lee el último SMS recibido | ultimo mensaje / ultimo sms / lee el ultimo mensaje |
| `ejecutar escaneo` | Random Forest (scikit-learn) | ejecuta escaner / ejecutar escanear |
| `ejecutar deep uno` | Red neuronal (Keras/TensorFlow) | deep/dip/dep/the + uno/1/primero |
| `ejecutar deep dos` | Red neuronal (PyTorch) | deep/dip/dep/the + dos/2/segundo |
| `pregunta a ollama [texto]` | Consulta al LLM local | preguntale a ollama... / consulta a ollama... |
| `ayuda` / `help` | Muestra la lista de comandos | — |
| `clear` / `cls` / `limpiar` | Limpia la pantalla | — |
| `salir` / `apagate` | Cierra Errik | — |

---

## 📦 Requisitos

- **Python 3.10+**
- Dependencias de Python:
  ```
  faster-whisper
  SpeechRecognition
  joblib
  requests
  scikit-learn
  ```
  Opcionales (para los modelos deep): `tensorflow`, `torch`.
- **En Android:** la app de [Termux](https://termux.dev) y [Termux:API](https://f-droid.org/packages/com.termux.api/) (para `termux-telephony-call`, `termux-tts-speak`, `termux-sms-list`).
- **Ollama** corriendo en `localhost:11434` con el modelo `llama3.1:8b` (solo para el comando de LLM).

---

## 🚀 Instalación en Android (Termux)

```bash
# 1. Paquetes base
pkg update && pkg upgrade
pkg install git python termux-api

# 2. Clonar el repositorio
git clone https://github.com/A-Buyin/Erik_Androy.git
cd Erik_Androy

# 3. Dependencias de Python
pip install faster-whisper SpeechRecognition joblib requests scikit-learn

# 4. Ejecutar (modo voz)
python Erik.py
```

### Actualizar a la última versión
```bash
cd Erik_Androy
git pull
```

---

## 💻 Uso en desarrollo (Windows)

```bash
python Erik.py       # modo voz por consola
python Erik_app.py   # interfaz gráfica (Tkinter)
```

En Windows, las funciones de telefonía/SMS/TTS (que dependen de Termux) no están disponibles; el resto sí para pruebas.

---

## 📁 Estructura del proyecto

```
Erik.py                 # Asistente por voz (consola)
Erik_app.py             # Asistente con interfaz gráfica (Tkinter)
Modelos_IDS/            # Modelos de ML (placeholders)
  ├── ids_random_forest.pkl
  ├── deep_model_1.h5
  └── deep_model_2.pt
crear_modelos_placeholder.py   # Genera modelos de ejemplo
logo/                   # Recursos gráficos (logo de la app)
  └── Gemini_Generated_Image_wzw7aawzw7aawzw7.png
```

---

## 📝 Notas

- Los modelos en `Modelos_IDS/` son **placeholders**: la app carga el modelo y confirma que está listo, pero la inferencia con datos reales está pendiente de conectar (ver comentarios en `procesar()`).
- Los modelos pesados (Whisper, etc.) se descargan/generan en el dispositivo y **no** se versionan en el repositorio (ver `.gitignore`).
