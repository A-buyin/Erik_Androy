package com.example.erikpy

import android.content.Context
import android.media.MediaPlayer
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.io.File
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Reproduce texto con la VOZ SELECCIONADA por el usuario (servidor /tts del VPS),
 * con respaldo automático al TTS de Android. Compartido por el asistente principal,
 * las llamadas y el traductor, para que la voz elegida sea la misma en todo.
 *
 * La elección se guarda en SharedPreferences ("erik" / "voz").
 */
class VozErik(private val context: Context) {

    private var player: MediaPlayer? = null
    private val main = Handler(Looper.getMainLooper())

    fun vozSeleccionada(): String =
        context.getSharedPreferences("erik", 0).getString("voz", "Luis Moray") ?: "Luis Moray"

    fun guardarVoz(valor: String) {
        context.getSharedPreferences("erik", 0).edit().putString("voz", valor).apply()
    }

    /**
     * Habla [text] en [idioma] ("es"/"en") con la voz elegida. Si el servidor no
     * responde, usa [respaldo] (TTS de Android). [alTerminar] se invoca al terminar.
     */
    fun hablar(
        text: String,
        idioma: String = "es",
        alTerminar: (() -> Unit)? = null,
        respaldo: (String, (() -> Unit)?) -> Unit
    ) {
        if (BuildConfig.VOZ_URL.isBlank()) { respaldo(text, alTerminar); return }
        Thread {
            val audio = try {
                pedir(text, idioma)
            } catch (e: Exception) {
                android.util.Log.w("ErikVoz", "Voz clonada falló: ${e.javaClass.simpleName}: ${e.message}")
                null
            }
            main.post {
                if (audio != null && audio.exists() && audio.length() > 1024) {
                    reproducir(audio, text, alTerminar, respaldo)
                } else {
                    respaldo(text, alTerminar)
                }
            }
        }.start()
    }

    private fun pedir(text: String, idioma: String): File? {
        val body = JSONObject()
            .put("text", text).put("voz", vozSeleccionada()).put("idioma", idioma).toString()
        val conn = URL(BuildConfig.VOZ_URL).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 60000
            conn.setRequestProperty("Content-Type", "application/json")
            if (BuildConfig.OLLAMA_USER.isNotBlank()) {
                val cred = "${BuildConfig.OLLAMA_USER}:${BuildConfig.OLLAMA_PASSWORD}"
                conn.setRequestProperty(
                    "Authorization",
                    "Basic " + android.util.Base64.encodeToString(
                        cred.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
                    )
                )
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            if (conn.responseCode !in 200..299) {
                android.util.Log.w("ErikVoz", "TTS HTTP ${conn.responseCode}")
                return null
            }
            val f = File(context.cacheDir, "voz_erik.wav")
            conn.inputStream.use { input -> f.outputStream().use { out -> input.copyTo(out) } }
            return f
        } finally {
            conn.disconnect()
        }
    }

    private fun reproducir(
        archivo: File, text: String,
        alTerminar: (() -> Unit)?, respaldo: (String, (() -> Unit)?) -> Unit
    ) {
        try {
            player?.release()
            player = MediaPlayer().apply {
                setDataSource(archivo.absolutePath)
                setOnCompletionListener {
                    it.release(); if (player === it) player = null
                    alTerminar?.let { cb -> main.post { cb() } }
                }
                setOnErrorListener { mp, _, _ ->
                    mp.release(); if (player === mp) player = null
                    respaldo(text, alTerminar); true
                }
                prepare()
                start()
            }
        } catch (e: Exception) {
            respaldo(text, alTerminar)
        }
    }

    fun liberar() { player?.release(); player = null }
}
