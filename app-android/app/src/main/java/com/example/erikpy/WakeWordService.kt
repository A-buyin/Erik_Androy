package com.example.erikpy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale

/**
 * Escucha permanente con el reconocedor de GOOGLE (el que sí funciona en el
 * teléfono), con máquina de estados y SILENCIADO DE ECO:
 *
 *  - ESPERA: escucha hasta oír "hola Erik". Si la frase trae la orden
 *    ("Erik llama a Hilda") la ejecuta; si es solo "hola Erik", saluda y pasa a COMANDO.
 *  - COMANDO: la siguiente frase se ejecuta como orden (aunque no diga "Erik").
 *  - Mientras Erik HABLA (TTS) NO escucha, para no oírse a sí mismo.
 *
 * Requiere RECORD_AUDIO, FOREGROUND_SERVICE_MICROPHONE y notificación permanente.
 */
class WakeWordService : Service() {

    companion object {
        private const val CHANNEL_ID = "erik_wake"
        private const val NOTIF_ID = 1001
        private const val VENTANA_COMANDO_MS = 10000L
        // Aviso a la app de que la escucha se apagó por voz (para actualizar el interruptor).
        const val ACTION_WAKE_OFF = "com.example.erikpy.WAKE_OFF"
    }

    private enum class Estado { ESPERA, COMANDO }

    private var recognizer: SpeechRecognizer? = null
    private var tts: TextToSpeech? = null
    private var handler: CommandHandler? = null
    private val main = Handler(Looper.getMainLooper())
    private var audio: AudioManager? = null
    @Volatile private var bipMute = false   // true = pitido del reconocedor silenciado

    @Volatile private var activo = false
    @Volatile private var estado = Estado.ESPERA
    @Volatile private var pendientesTts = 0   // >0 = Erik está hablando (no escuchar)
    private var idTts = 0

    // Activación: "hola Erik", "actívate Erik", "oye Erik"…
    private val reWake = Regex("(?i)\\b(?:hola\\s+|oye\\s+|hey\\s+|ola\\s+|activ\\w*\\s+)?(?:erik|eric|erick|herik|érik)\\b")
    // Apagar y liberar el micrófono: "desactívate", "apaga el micrófono", "deja de escuchar".
    private val reApagar = Regex(
        "(?i)\\b(?:desactiv\\w*|apag\\w*\\s+(?:el\\s+)?(?:microfono|micrófono|erik)|deja\\s+de\\s+escuchar|silencia\\w*\\s+erik)\\b"
    )

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        audio = getSystemService(AudioManager::class.java)
        tts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale.forLanguageTag("es-ES")
        }
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) = finHabla()
            override fun onError(utteranceId: String?) = finHabla()
        })
        handler = CommandHandler(applicationContext) { texto -> hablar(texto) }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startForegroundConNotificacion("Erik escucha. Di \"actívate Erik\" o \"hola Erik\"; \"desactívate\" para liberar el micrófono.")
        desmutearTodo()   // recupera el volumen por si una versión previa lo dejó silenciado
        activo = true
        main.post {
            if (!SpeechRecognizer.isRecognitionAvailable(this)) {
                notificar("El reconocimiento de voz no está disponible."); return@post
            }
            recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
                setRecognitionListener(oyente)
            }
            // Pequeño retardo para no captar audio residual del arranque.
            main.postDelayed({ escuchar() }, 1000)
        }
        return START_STICKY
    }

    // --- Habla con silenciado de eco (no escucha mientras suena el TTS) ---

    private fun hablar(texto: String) {
        pendientesTts++
        try { recognizer?.cancel() } catch (e: Exception) {}
        tts?.speak(texto, TextToSpeech.QUEUE_ADD, null, "u${++idTts}")
    }

    private fun finHabla() {
        main.post {
            pendientesTts = maxOf(0, pendientesTts - 1)
            if (pendientesTts == 0 && activo) escuchar()   // al callar, vuelve a escuchar
        }
    }

    // --- Escucha (Google) ---

    // Recupera el volumen por si una versión anterior (la del pitido) dejó algo silenciado.
    private val streamsBip = intArrayOf(
        AudioManager.STREAM_MUSIC, AudioManager.STREAM_SYSTEM, AudioManager.STREAM_NOTIFICATION
    )

    private fun desmutearTodo() {
        for (s in streamsBip) try { audio?.adjustStreamVolume(s, AudioManager.ADJUST_UNMUTE, 0) } catch (e: Exception) {}
        bipMute = false
    }

    private fun escuchar() {
        if (!activo || pendientesTts > 0) return
        try {
            recognizer?.startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
                // Alarga cada sesión de escucha para reiniciar (y pitar) menos veces.
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_COMPLETE_SILENCE_LENGTH_MILLIS, 3000)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_POSSIBLY_COMPLETE_SILENCE_LENGTH_MILLIS, 3000)
                putExtra(RecognizerIntent.EXTRA_SPEECH_INPUT_MINIMUM_LENGTH_MILLIS, 6000)
            })
        } catch (e: Exception) {
            android.util.Log.e("ErikVoz", "startListening falló: ${e.message}")
            reintentar(1500)
        }
    }

    private fun reintentar(delayMs: Long = 350) {
        if (!activo) return
        main.postDelayed({
            if (activo && pendientesTts == 0) {
                try { recognizer?.cancel() } catch (e: Exception) {}
                escuchar()
            }
        }, delayMs)
    }

    private val oyente = object : android.speech.RecognitionListener {
        override fun onResults(results: Bundle?) {
            val texto = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            if (texto.isNotBlank()) android.util.Log.i("ErikVoz", "oyó (Google): $texto")
            val actuo = procesar(texto)
            if (!actuo) reintentar(1000)   // si actuó, el TTS reanudará la escucha al callar
        }
        override fun onError(error: Int) {
            if (pendientesTts > 0) return   // estamos hablando; ya se reanudará
            // Pausas más largas al no oír nada -> reinicia (y pita) mucho menos seguido.
            val espera = when (error) {
                SpeechRecognizer.ERROR_RECOGNIZER_BUSY -> 1800L
                SpeechRecognizer.ERROR_NO_MATCH, SpeechRecognizer.ERROR_SPEECH_TIMEOUT -> 2000L
                else -> 900L
            }
            reintentar(espera)
        }
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    /** Devuelve true si atendió algo (y por tanto habrá TTS que reanuda la escucha). */
    private fun procesar(texto: String): Boolean {
        if (texto.isBlank()) return false

        // "Desactívate" en cualquier momento: apaga y libera el micrófono.
        if (reApagar.containsMatchIn(texto)) { desactivar(); return true }

        if (estado == Estado.COMANDO) {
            estado = Estado.ESPERA
            android.util.Log.i("ErikVoz", "Ejecutando comando: $texto")
            handler?.handle(texto)
            return true
        }

        // Estado ESPERA: debe contener "Erik".
        val m = reWake.find(texto) ?: return false
        android.util.Log.i("ErikVoz", "Palabra de activación detectada")
        val resto = texto.substring(m.range.last + 1).trim().trim { it in ".,;:!?¿¡ " }
        return if (resto.length >= 3) {
            android.util.Log.i("ErikVoz", "Comando en la misma frase: $resto")
            handler?.handle(resto)
            true
        } else {
            estado = Estado.COMANDO
            main.postDelayed({ if (estado == Estado.COMANDO) estado = Estado.ESPERA }, VENTANA_COMANDO_MS)
            hablar("Hola Ariel, ¿en qué te puedo ayudar?")
            true
        }
    }

    /** Apaga la escucha y LIBERA el micrófono para otras apps. */
    private fun desactivar() {
        android.util.Log.i("ErikVoz", "Desactivación por voz: libero el micrófono.")
        activo = false
        try { recognizer?.cancel(); recognizer?.destroy() } catch (e: Exception) {}
        recognizer = null   // el micrófono queda libre de inmediato
        // Avisa a la app para que apague el interruptor y muestre el mensaje.
        sendBroadcast(Intent(ACTION_WAKE_OFF).setPackage(packageName))
        tts?.speak("Erik desactivado, Ariel. Micrófono liberado.",
            TextToSpeech.QUEUE_FLUSH, null, "off")
        main.postDelayed({ stopSelf() }, 3000)
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
        activo = false
        desmutearTodo()   // por si quedó algún canal silenciado de una versión anterior
        try { recognizer?.destroy() } catch (e: Exception) {}
        recognizer = null
        tts?.stop(); tts?.shutdown(); tts = null
    }
}
