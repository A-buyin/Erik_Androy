package com.example.erikpy

import android.content.Context
import android.net.Uri
import android.os.Handler
import android.os.Looper
import android.provider.Telephony
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL

/**
 * Extrae por VOZ los datos de un documento de despacho a partir de la última foto
 * (MMS) recibida de un contacto (Despach 1..4): número de contenedor y dirección.
 *
 * Flujo: busca el MMS con imagen de ese número -> OCR (ML Kit) -> contenedor por
 * regex + dirección con el modelo del VPS -> locuta el resultado por [speak].
 */
class DespachoExtractor(
    private val context: Context,
    private val speak: (String) -> Unit
) {
    private val ocr = TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS)
    private val reContenedor = Regex("\\b([A-Z]{4})\\s?(\\d{7})\\b")
    // Dirección EE.UU.: número de calle + … + estado (2 letras) + código postal (5 dígitos).
    // Ej: "9715 KLINGERMAN ST S EL MONTE CA 91733".
    private val reDireccion = Regex("\\b\\d{2,6}\\s+[A-Za-z0-9 .,'#/-]{3,70}?[A-Z]{2}\\s?\\d{5}\\b")
    private val main = Handler(Looper.getMainLooper())

    private fun say(t: String) = main.post { speak(t) }

    /** Punto de entrada: procesa la última foto de despacho de [nombre] (número [numero]). */
    fun procesarUltimoDe(nombre: String, numero: String) {
        say("Buscando el último despacho de $nombre, Ariel...")
        Thread {
            val uri = try { ultimaImagenMmsDe(numero) } catch (e: Exception) {
                android.util.Log.e("ErikVoz", "MMS falló: ${e.message}", e)
                say("No pude leer los mensajes de $nombre, Ariel."); return@Thread
            }
            if (uri == null) {
                say("No encontré una foto reciente de $nombre en tus mensajes, Ariel."); return@Thread
            }
            android.util.Log.i("ErikVoz", "Imagen MMS encontrada: $uri")
            main.post {
                try {
                    val imagen = InputImage.fromFilePath(context, uri)
                    ocr.process(imagen)
                        .addOnSuccessListener { vt -> onOcr(nombre, vt.text) }
                        .addOnFailureListener { say("No pude leer la foto de $nombre, Ariel.") }
                } catch (e: Exception) {
                    say("No pude abrir la foto de $nombre, Ariel.")
                }
            }
        }.start()
    }

    private fun onOcr(nombre: String, textoRaw: String) {
        val texto = textoRaw.replace("\n", " ").replace(Regex("\\s+"), " ").trim()
        if (texto.isBlank()) { say("La foto de $nombre no tiene texto legible, Ariel."); return }
        val textoMayus = texto.uppercase()
        val contenedorRegex = reContenedor.find(textoMayus)
            ?.let { "${it.groupValues[1]}${it.groupValues[2]}" }
        val direccionRegex = reDireccion.find(textoMayus)?.value?.trim()
        // Si ya tengo ambos por patrón exacto, ni consulto el modelo (más rápido y fiable).
        if (contenedorRegex != null && direccionRegex != null) {
            say("Despacho de $nombre. Contenedor: $contenedorRegex. Dirección: $direccionRegex.")
            return
        }
        Thread {
            val resp = try { extraerConModelo(texto) } catch (e: Exception) { null }
            val contenedor = contenedorRegex ?: campo(resp, "Contenedor") ?: "no encontrado"
            val direccion = direccionRegex ?: campo(resp, "Dirección") ?: campo(resp, "Direccion") ?: "no encontrada"
            say("Despacho de $nombre. Contenedor: $contenedor. Dirección: $direccion.")
        }.start()
    }

    // --- Búsqueda del MMS con imagen del número dado ---

    private fun ultimaImagenMmsDe(numero: String): Uri? {
        // Vía rápida: el hilo de conversación de ese número (solo sus mensajes).
        val threadId = try {
            Telephony.Threads.getOrCreateThreadId(context, numero)
        } catch (e: Exception) { -1L }
        if (threadId >= 0) {
            context.contentResolver.query(
                Uri.parse("content://mms"), arrayOf("_id"),
                "thread_id=? AND msg_box=1", arrayOf(threadId.toString()), "date DESC"
            )?.use { c ->
                while (c.moveToNext()) {
                    val mmsId = c.getString(0) ?: continue
                    val partId = imagenPartId(mmsId) ?: continue
                    return Uri.parse("content://mms/part/$partId")
                }
            }
        }
        // Respaldo: recorre el inbox y filtra por remitente (por si el hilo falla).
        val clave = numero.filter { it.isDigit() }.takeLast(7)
        if (clave.isEmpty()) return null
        context.contentResolver.query(
            Uri.parse("content://mms/inbox"), arrayOf("_id"), null, null, "date DESC"
        )?.use { c ->
            var revisados = 0
            while (c.moveToNext() && revisados < 300) {   // límite para no colgarse
                revisados++
                val mmsId = c.getString(0) ?: continue
                if (!mmsEsDe(mmsId, clave)) continue
                val partId = imagenPartId(mmsId) ?: continue
                return Uri.parse("content://mms/part/$partId")
            }
        }
        return null
    }

    /** ¿El MMS [mmsId] viene del número cuyos últimos 7 dígitos son [clave7]? */
    private fun mmsEsDe(mmsId: String, clave7: String): Boolean {
        context.contentResolver.query(
            Uri.parse("content://mms/$mmsId/addr"), arrayOf("address", "type"), null, null, null
        )?.use { c ->
            while (c.moveToNext()) {
                val addr = c.getString(0) ?: continue
                val type = c.getInt(1)
                if (type == 137) {   // 137 = remitente (FROM)
                    val d = addr.filter { it.isDigit() }
                    if (d.isNotEmpty() && d.takeLast(7) == clave7) return true
                }
            }
        }
        return false
    }

    private fun imagenPartId(mmsId: String): String? {
        context.contentResolver.query(
            Uri.parse("content://mms/part"), arrayOf("_id", "ct"), "mid=?", arrayOf(mmsId), null
        )?.use { c ->
            while (c.moveToNext()) {
                val ct = c.getString(1) ?: ""
                if (ct.startsWith("image/")) return c.getString(0)
            }
        }
        return null
    }

    // --- Extracción de la dirección con el modelo del VPS ---

    private fun extraerConModelo(texto: String): String? {
        if (BuildConfig.OLLAMA_URL.isBlank()) return null
        val prompt = """
El siguiente es el texto (OCR) de un documento de despacho de contenedores.
Básate SOLO en el texto impreso; IGNORA anotaciones a mano, resaltados y sellos.
Devuelve EXACTAMENTE dos líneas:
Contenedor: <4 letras y 7 dígitos>
Dirección: <dirección de entrega tras "DEL:": calle, ciudad, estado y código postal, sin empresa ni teléfono>
Si un dato no aparece, escribe "no encontrado". Nada más.

TEXTO:
$texto
""".trim()
        val body = JSONObject().apply {
            put("model", BuildConfig.OLLAMA_MODEL)
            put("prompt", prompt)
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
                conn.setRequestProperty(
                    "Authorization",
                    "Basic " + android.util.Base64.encodeToString(
                        cred.toByteArray(Charsets.UTF_8), android.util.Base64.NO_WRAP
                    )
                )
            }
            OutputStreamWriter(conn.outputStream, Charsets.UTF_8).use { it.write(body) }
            if (conn.responseCode !in 200..299) return null
            val txt = BufferedReader(InputStreamReader(conn.inputStream, Charsets.UTF_8)).use { it.readText() }
            return JSONObject(txt).optString("response").trim()
        } finally {
            conn.disconnect()
        }
    }

    private fun campo(resp: String?, campo: String): String? {
        if (resp.isNullOrBlank()) return null
        for (linea in resp.lines()) {
            val l = linea.trim().removePrefix("-").trim()
            if (l.startsWith(campo, ignoreCase = true) && l.contains(":")) {
                val v = l.substringAfter(":").trim()
                if (v.isNotEmpty() && !v.equals("no encontrado", true) && !v.equals("no encontrada", true)) return v
            }
        }
        return null
    }
}
