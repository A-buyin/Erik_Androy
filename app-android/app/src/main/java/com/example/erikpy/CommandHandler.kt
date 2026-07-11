package com.example.erikpy

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.provider.ContactsContract
import android.provider.Telephony
import android.telecom.TelecomManager
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import java.text.Normalizer
import java.util.Locale

/**
 * Cerebro de Erik sin dependencia de la interfaz: reconoce comandos, consulta al
 * modelo del VPS y ejecuta acciones (llamar, colgar, leer SMS). Lo usan tanto el
 * fragmento (botón/voz) como el servicio de escucha permanente (WakeWordService).
 *
 * Las respuestas se entregan por el callback [speak], SIEMPRE en el hilo principal
 * (para que sea seguro tocar la UI y el TTS desde cualquier hilo).
 *
 * Los permisos (CALL_PHONE, ANSWER_PHONE_CALLS, READ_CONTACTS, READ_SMS) se piden
 * en la interfaz; aquí se asume que ya están concedidos y, si faltan, se avisa.
 */
class CommandHandler(
    private val context: Context,
    private val speak: (String) -> Unit,
) {
    data class Contacto(val nombre: String, val numero: String)

    private val mainHandler = Handler(Looper.getMainLooper())

    private val patronLlamada = Regex(
        // llam* cubre llama/llamar/llamada/llamando; marc* cubre marca/marcar/marcando.
        "^\\s*(?:llam\\w*|lama|yama|marc\\w*|telefone\\w*)\\s+(?:al?\\s+)?(.+)$",
        RegexOption.IGNORE_CASE
    )
    private val patronUltimoMsj = Regex("\\bultim\\w*\\s+(?:mensaje|mensajes|sms)")
    private val patronColgar = Regex("\\bcuelg\\w*\\b|\\bcolg\\w*\\b|\\bcort\\w*\\s+la\\s+llamad\\w*")
    private val patronOllama = Regex(
        "(?:pregunt\\w*|consult\\w*)\\s+a\\s+(?:o\\s*llama|ollama|olama|oyama|llama)\\b\\s*(.*)$"
    )

    private val systemPromptErik = """
Eres Erik, el asistente personal de Ariel. Nunca digas que eres un modelo de lenguaje: eres Erik.
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

Si no comprendes la instrucción, di exactamente: "Lo siento Ariel, no comprendí la instrucción. ¿Podrías repetirla?" y no pongas JSON.
Trata la información de contactos con total confidencialidad. Solo ejecutas tareas; no expliques cómo funcionas.
""".trim()

    /** Emite una respuesta en el hilo principal (segura para UI y TTS). */
    private fun say(text: String) {
        android.util.Log.i("ErikVoz", "Erik responde: $text")
        mainHandler.post { speak(text) }
    }

    fun normalizar(texto: String): String {
        val nfd = Normalizer.normalize(texto, Normalizer.Form.NFD)
        val sinAcentos = nfd.replace(Regex("\\p{Mn}+"), "")
        return sinAcentos.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
    }

    /** Procesa un comando ya reconocido (por voz o texto): ejecuta la acción
     *  directa o, si no es un comando conocido, se lo pasa al modelo del VPS. */
    fun handle(command: String) {
        android.util.Log.i("ErikVoz", "Comando: $command")
        val cmdLower = command.trim().lowercase(Locale.ROOT)
        val cmdNorm = normalizar(command)

        if (patronColgar.containsMatchIn(cmdNorm)) { hangUpCall(); return }
        if (patronUltimoMsj.containsMatchIn(cmdNorm)) { readLastSms(); return }

        val mLlamada = patronLlamada.find(cmdLower)
        if (mLlamada != null) {
            val target = mLlamada.groupValues[1].trim().trim { it in ".,;:!?¿¡ " }
            if (target.isEmpty()) { say("¿A quién quieres llamar, Ariel?"); return }
            if (target.matches(Regex("^[+0-9 \\-]+$"))) {
                placeCall(target.replace(" ", ""))
            } else {
                val c = lookupContact(target)
                if (c != null) { say("Llamando a ${c.nombre}, Ariel."); placeCall(c.numero) }
                else say("No encontré a $target en tus contactos, Ariel.")
            }
            return
        }

        // "pregunta a ollama X" o cualquier otra cosa -> modelo del VPS (cerebro).
        val mOllama = patronOllama.find(cmdNorm)
        val pregunta = mOllama?.groupValues?.getOrNull(1)?.trim().orEmpty()
        askErik(if (mOllama != null && pregunta.isNotEmpty()) pregunta else command)
    }

    // --- Modelo Ollama del VPS ---

    /** Consulta al modelo (en segundo plano), locuta la respuesta y ejecuta las
     *  acciones JSON que devuelva. */
    fun askErik(command: String) {
        if (BuildConfig.OLLAMA_URL.isBlank()) {
            say("El asistente con IA no está configurado, Ariel."); return
        }
        say("Un momento, Ariel...")
        Thread {
            val respuesta = try { callOllama(command) } catch (e: Exception) { null }
            if (respuesta == null) {
                say("No pude consultar al asistente, Ariel. Revisa la conexión.")
                return@Thread
            }
            val (texto, acciones) = extractActions(respuesta)
            if (texto.isNotBlank()) say(texto)
            for (i in 0 until acciones.length()) {
                val a = acciones.optJSONObject(i) ?: continue
                mainHandler.post { executeAction(a) }
            }
        }.start()
    }

    private fun callOllama(command: String): String? {
        val body = JSONObject().apply {
            put("model", BuildConfig.OLLAMA_MODEL)
            put("prompt", command)
            put("system", systemPromptErik)
            put("stream", false)
            put("keep_alive", "30m")
            put("options", JSONObject().put("num_predict", 200))
        }.toString()

        val conn = URL(BuildConfig.OLLAMA_URL).openConnection() as HttpURLConnection
        try {
            conn.requestMethod = "POST"
            conn.doOutput = true
            conn.connectTimeout = 15000
            conn.readTimeout = 120000
            conn.setRequestProperty("Content-Type", "application/json")
            if (BuildConfig.OLLAMA_USER.isNotBlank()) {
                val cred = "${BuildConfig.OLLAMA_USER}:${BuildConfig.OLLAMA_PASSWORD}"
                val basic = "Basic " + android.util.Base64.encodeToString(
                    cred.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
                )
                conn.setRequestProperty("Authorization", basic)
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            if (conn.responseCode !in 200..299) {
                android.util.Log.w("ErikVoz", "VPS HTTP ${conn.responseCode}")
                return null
            }
            val texto = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
            val resp = JSONObject(texto).optString("response").trim()
            android.util.Log.i("ErikVoz", "Respuesta del modelo: $resp")
            return resp
        } finally {
            conn.disconnect()
        }
    }

    private fun extractActions(respuesta: String): Pair<String, JSONArray> {
        val m = Regex("\\[\\s*\\{.*?\\}\\s*\\]", RegexOption.DOT_MATCHES_ALL).find(respuesta)
        if (m != null) {
            try {
                val arr = JSONArray(m.value)
                return Pair(respuesta.replace(m.value, "").trim(), arr)
            } catch (e: Exception) { /* JSON inválido: se lee todo como texto */ }
        }
        return Pair(respuesta.trim(), JSONArray())
    }

    private fun executeAction(action: JSONObject) {
        when (action.optString("accion").lowercase(Locale.ROOT).trim()) {
            "llamar" -> {
                val numero = action.optString("numero").trim()
                val contacto = action.optString("contacto").trim()
                if (numero.isNotEmpty() && numero.matches(Regex("^[+0-9 \\-]+$"))) {
                    placeCall(numero.replace(" ", ""))
                } else if (contacto.isNotEmpty()) {
                    val c = lookupContact(contacto)
                    if (c != null) { say("Llamando a ${c.nombre}, Ariel."); placeCall(c.numero) }
                    else say("No encontré a $contacto en tus contactos, Ariel.")
                } else say("No entendí a quién llamar, Ariel.")
            }
            "colgar" -> hangUpCall()
            "leer_mensaje", "leer_sms", "ultimo_mensaje" -> readLastSms()
        }
    }

    // --- Contactos ---

    fun lookupContact(name: String, umbral: Double = 0.62): Contacto? {
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_CONTACTS
            ) != PackageManager.PERMISSION_GRANTED
        ) return null

        val obj = normalizar(name)
        if (obj.isEmpty()) return null

        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        var mejor: Contacto? = null
        var mejorScore = 0.0
        context.contentResolver.query(uri, projection, null, null, null)?.use { c ->
            while (c.moveToNext()) {
                val nombre = c.getString(0) ?: continue
                val numero = c.getString(1) ?: continue
                val nombreNorm = normalizar(nombre)
                if (nombreNorm.isEmpty()) continue
                var score = ratio(obj, nombreNorm)
                for (parte in nombreNorm.split(" ")) {
                    score = if (parte == obj) maxOf(score, 0.98) else maxOf(score, ratio(obj, parte) * 0.85)
                }
                if (nombreNorm.contains(obj) || obj.contains(nombreNorm)) score = maxOf(score, 0.9)
                if (score > mejorScore) { mejorScore = score; mejor = Contacto(nombre, numero) }
            }
        }
        return if (mejor != null && mejorScore >= umbral) mejor else null
    }

    private fun ratio(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / maxLen
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]; dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                val costo = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + costo)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    // --- Telefonía ---

    @SuppressLint("MissingPermission")
    private fun placeCall(number: String) {
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.CALL_PHONE
            ) != PackageManager.PERMISSION_GRANTED
        ) { say("No tengo permiso para llamar, Ariel."); return }

        val uri = Uri.fromParts("tel", number, null)
        val tm = context.getSystemService(TelecomManager::class.java)
        try {
            // placeCall funciona también desde el servicio en segundo plano.
            tm?.placeCall(uri, null)
        } catch (e: Exception) {
            // Respaldo: intent de llamada (desde la app en primer plano).
            val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            try { context.startActivity(intent) } catch (e2: Exception) {
                say("No pude iniciar la llamada, Ariel.")
            }
        }
    }

    @SuppressLint("MissingPermission")
    private fun hangUpCall() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.P) {
            say("Colgar por voz necesita Android 9 o superior, Ariel."); return
        }
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.ANSWER_PHONE_CALLS
            ) != PackageManager.PERMISSION_GRANTED
        ) { say("No tengo permiso para colgar, Ariel."); return }

        val tm = context.getSystemService(TelecomManager::class.java)
        if (tm == null) { say("No pude acceder a la telefonía, Ariel."); return }
        try {
            val terminada = tm.endCall()
            say(if (terminada) "Llamada finalizada, Ariel." else "No hay ninguna llamada activa, Ariel.")
        } catch (e: SecurityException) {
            say("No tengo permiso para colgar, Ariel.")
        }
    }

    private fun readLastSms() {
        if (ContextCompat.checkSelfPermission(
                context, Manifest.permission.READ_SMS
            ) != PackageManager.PERMISSION_GRANTED
        ) { say("Sin permiso para leer mensajes, Ariel."); return }

        val projection = arrayOf(Telephony.Sms.ADDRESS, Telephony.Sms.BODY)
        context.contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI, projection, null, null,
            "${Telephony.Sms.DATE} DESC"
        )?.use { c ->
            if (c.moveToFirst()) {
                val from = c.getString(0) ?: "desconocido"
                val body = c.getString(1) ?: ""
                say("El último mensaje es de $from y dice: $body")
                return
            }
        }
        say("No hay mensajes en la bandeja de entrada, Ariel.")
    }
}
