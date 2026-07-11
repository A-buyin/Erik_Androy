package com.example.erikpy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import org.json.JSONObject
import org.vosk.Model
import org.vosk.Recognizer
import org.vosk.android.RecognitionListener
import org.vosk.android.SpeechService
import java.io.File
import java.net.URL
import java.text.Normalizer
import java.util.Locale
import java.util.zip.ZipInputStream

/**
 * Servicio en primer plano que escucha SIEMPRE (app abierta o cerrada) la palabra
 * de activación "hola Erik" con el motor offline Vosk. Al detectarla, saluda y
 * captura la siguiente frase como comando, que ejecuta con CommandHandler.
 *
 * Funciona en DOS fases para máxima precisión:
 *  1) Fase ESPERA: reconocedor con GRAMÁTICA limitada ("hola erik", "erik"...).
 *     Con el modelo pequeño esto detecta la palabra clave mucho mejor.
 *  2) Fase COMANDO: reconocedor LIBRE para captar cualquier orden (nombres, etc.).
 */
class WakeWordService : Service() {

    companion object {
        const val MODEL_URL = "https://alphacephei.com/vosk/models/vosk-model-small-es-0.42.zip"
        const val MODEL_DIR = "vosk-model-small-es-0.42"
        private const val CHANNEL_ID = "erik_wake"
        private const val NOTIF_ID = 1001
        private const val SAMPLE_RATE = 16000.0f
        // Solo estas frases en la fase de espera -> detección de "Erik" muy fiable.
        private const val WAKE_GRAMMAR =
            "[\"hola erik\", \"oye erik\", \"hey erik\", \"ola erik\", \"erik\", \"[unk]\"]"
    }

    private enum class Fase { ESPERA, SALUDANDO, COMANDO }

    private var model: Model? = null
    private var speechService: SpeechService? = null
    private var tts: TextToSpeech? = null
    private var handler: CommandHandler? = null
    private val main = Handler(Looper.getMainLooper())

    @Volatile private var fase = Fase.ESPERA

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale.forLanguageTag("es-ES")
        }
        handler = CommandHandler(applicationContext) { texto ->
            tts?.speak(texto, TextToSpeech.QUEUE_ADD, null, "erik")
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundConNotificacion("Preparando a Erik...")
        Thread {
            val path = try { ensureModel() } catch (e: Exception) {
                android.util.Log.e("ErikVoz", "Error con el modelo: ${e.message}"); null
            }
            if (path == null) { notificar("No pude preparar el reconocimiento de voz."); return@Thread }
            try {
                model = Model(path)
                escucharEspera()
                notificar("Erik está escuchando. Di \"hola Erik\".")
            } catch (e: Exception) {
                android.util.Log.e("ErikVoz", "Error iniciando Vosk: ${e.message}")
                notificar("No pude iniciar la escucha.")
            }
        }.start()
        return START_STICKY
    }

    // --- Fase 1: espera de la palabra de activación (gramática limitada) ---

    private fun escucharEspera() {
        fase = Fase.ESPERA
        reiniciarReconocedor(Recognizer(model, SAMPLE_RATE, WAKE_GRAMMAR)) {
            object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {
                    if (fase == Fase.ESPERA && contieneWake(textoDe(hypothesis, "partial"))) despertar()
                }
                override fun onResult(hypothesis: String?) {
                    val t = textoDe(hypothesis, "text")
                    if (t.isNotBlank()) android.util.Log.i("ErikVoz", "Vosk(espera) oyó: $t")
                    if (fase == Fase.ESPERA && contieneWake(t)) despertar()
                }
                override fun onFinalResult(hypothesis: String?) {}
                override fun onError(exception: Exception?) {
                    android.util.Log.e("ErikVoz", "Vosk error (espera): ${exception?.message}")
                }
                override fun onTimeout() {}
            }
        }
    }

    /** Oyó "hola Erik": saluda y, al terminar de hablar, pasa a captar el comando. */
    private fun despertar() {
        if (fase != Fase.ESPERA) return
        fase = Fase.SALUDANDO
        android.util.Log.i("ErikVoz", "Palabra de activación detectada")
        pararReconocedor()
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onError(utteranceId: String?) { main.post { escucharEspera() } }
            override fun onDone(utteranceId: String?) {
                if (utteranceId == "saludo") main.post { escucharComando() }
            }
        })
        tts?.speak("Hola Ariel, ¿en qué te puedo ayudar?", TextToSpeech.QUEUE_FLUSH, null, "saludo")
    }

    // --- Fase 2: captar el comando (reconocimiento libre) ---

    private fun escucharComando() {
        fase = Fase.COMANDO
        reiniciarReconocedor(Recognizer(model, SAMPLE_RATE)) {
            object : RecognitionListener {
                override fun onPartialResult(hypothesis: String?) {}
                override fun onResult(hypothesis: String?) {
                    val t = textoDe(hypothesis, "text")
                    if (fase == Fase.COMANDO && t.isNotBlank() && !esSoloWake(t)) {
                        fase = Fase.ESPERA
                        android.util.Log.i("ErikVoz", "Comando por voz: $t")
                        handler?.handle(t)
                        main.postDelayed({ escucharEspera() }, 1500)
                    }
                }
                override fun onFinalResult(hypothesis: String?) {}
                override fun onError(exception: Exception?) {
                    android.util.Log.e("ErikVoz", "Vosk error (comando): ${exception?.message}")
                    main.post { escucharEspera() }
                }
                override fun onTimeout() {}
            }
        }
        // Si en 8 s no dice nada, vuelve a la fase de espera.
        main.postDelayed({ if (fase == Fase.COMANDO) escucharEspera() }, 8000)
    }

    // --- Gestión del reconocedor / micrófono ---

    private fun reiniciarReconocedor(recognizer: Recognizer, listener: () -> RecognitionListener) {
        pararReconocedor()
        main.post {
            try {
                val service = SpeechService(recognizer, SAMPLE_RATE)
                speechService = service
                service.startListening(listener())
            } catch (e: Exception) {
                android.util.Log.e("ErikVoz", "No pude abrir el micrófono: ${e.message}")
            }
        }
    }

    private fun pararReconocedor() {
        try { speechService?.stop() } catch (e: Exception) {}
        try { speechService?.shutdown() } catch (e: Exception) {}
        speechService = null
    }

    // --- Utilidades de texto ---

    private fun textoDe(hypothesis: String?, clave: String): String =
        try { JSONObject(hypothesis ?: "{}").optString(clave) } catch (e: Exception) { "" }

    private fun normalizar(texto: String): String {
        val nfd = Normalizer.normalize(texto, Normalizer.Form.NFD)
        return nfd.replace(Regex("\\p{Mn}+"), "").lowercase(Locale.ROOT)
            .replace(Regex("\\s+"), " ").trim()
    }

    private fun contieneWake(texto: String): Boolean {
        val t = normalizar(texto)
        return t.contains("erik") || t.contains("eric") || t.contains("herik")
    }

    private fun esSoloWake(texto: String): Boolean {
        val t = normalizar(texto).replace("hola", "").replace("oye", "").replace("hey", "").trim()
        return t.isEmpty() || t == "erik" || t == "eric" || t == "herik"
    }

    // --- Modelo Vosk: descarga + descompresión ---

    private fun ensureModel(): String? {
        val modelDir = File(filesDir, MODEL_DIR)
        if (File(modelDir, "conf").exists()) return modelDir.absolutePath
        notificar("Descargando voz de Erik (~38 MB)...")
        val zip = File(cacheDir, "model.zip")
        URL(MODEL_URL).openStream().use { input -> zip.outputStream().use { input.copyTo(it) } }
        notificar("Instalando la voz...")
        descomprimir(zip, filesDir)
        zip.delete()
        return if (File(modelDir, "conf").exists()) modelDir.absolutePath else null
    }

    private fun descomprimir(zipFile: File, targetDir: File) {
        ZipInputStream(zipFile.inputStream().buffered()).use { zis ->
            var entry = zis.nextEntry
            while (entry != null) {
                val outFile = File(targetDir, entry.name)
                if (entry.isDirectory) outFile.mkdirs()
                else { outFile.parentFile?.mkdirs(); outFile.outputStream().use { zis.copyTo(it) } }
                entry = zis.nextEntry
            }
        }
    }

    // --- Notificación de primer plano (obligatoria) ---

    private fun startForegroundConNotificacion(texto: String) {
        val nm = getSystemService(NotificationManager::class.java)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            nm.createNotificationChannel(
                NotificationChannel(CHANNEL_ID, "Escucha de Erik", NotificationManager.IMPORTANCE_LOW)
            )
        }
        val notif = construirNotif(texto)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif, ServiceInfo.FOREGROUND_SERVICE_TYPE_MICROPHONE)
        } else {
            startForeground(NOTIF_ID, notif)
        }
    }

    @Suppress("DEPRECATION")
    private fun construirNotif(texto: String): Notification {
        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            Notification.Builder(this, CHANNEL_ID) else Notification.Builder(this)
        return builder
            .setContentTitle("Erik")
            .setContentText(texto)
            .setSmallIcon(R.drawable.ic_mic)
            .setOngoing(true)
            .build()
    }

    private fun notificar(texto: String) {
        getSystemService(NotificationManager::class.java).notify(NOTIF_ID, construirNotif(texto))
    }

    override fun onDestroy() {
        super.onDestroy()
        pararReconocedor()
        model?.close(); model = null
        tts?.stop(); tts?.shutdown(); tts = null
    }
}
