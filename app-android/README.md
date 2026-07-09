# Errik — App Android nativa 🎙️

Asistente personal **Errik** en versión **Android nativa** (Kotlin). Recibe comandos por **texto** o por **voz** y responde en voz alta (TTS), en español.

> App complementaria a la versión Python de Errik (que corre en Termux). Esta es la app instalable en el teléfono.

## ✨ Características

- **Entrada por texto y por voz** (🎤 botón "Hablar" → reconocedor de voz de Android en español).
- **Emparejador tolerante de comandos**: acentos, mayúsculas y errores típicos de dictado.
- Responde en voz alta con **Text-to-Speech**.

## 🗣️ Comandos

| Comando | Acción | Variantes |
|---|---|---|
| `llama a [nombre o número]` | Busca en contactos y marca | marca / marcar / telefonea / llamar, con o sin "a"/"al" |
| `último mensaje` | Lee el último SMS recibido | último sms |
| `ayuda` | Muestra la lista de comandos | help |
| `salir` | Cierra Errik | apagate |

## 🛠️ Compilar

Proyecto de **Android Studio** (Kotlin, ViewBinding).

```bash
./gradlew assembleDebug
# APK: app/build/outputs/apk/debug/app-debug.apk
```

Instalar en un dispositivo conectado:
```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

## 🔐 Permisos

- `CALL_PHONE` — realizar llamadas
- `READ_CONTACTS` — buscar contactos por nombre
- `READ_SMS` — leer el último mensaje
- (El reconocimiento de voz lo gestiona la app de Google; puede pedir permiso de micrófono la primera vez.)

## 📦 Estructura

```
app/src/main/java/com/example/erikpy/
  ├── MainActivity.kt
  ├── FirstFragment.kt      # pantalla principal: comandos + voz
  └── SecondFragment.kt     # ayuda
app/src/main/res/           # layouts, strings, iconos
```
