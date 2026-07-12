package com.example.erikpy

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import android.view.View
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.core.content.IntentCompat
import org.json.JSONObject
import java.io.BufferedReader
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.HttpURLConnection
import java.net.URL
import com.example.erikpy.databinding.ActivityDocumentoBinding
import com.google.mlkit.common.model.DownloadConditions
import com.google.mlkit.nl.languageid.LanguageIdentification
import com.google.mlkit.nl.translate.TranslateLanguage
import com.google.mlkit.nl.translate.Translation
import com.google.mlkit.nl.translate.Translator
import com.google.mlkit.nl.translate.TranslatorOptions
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.latin.TextRecognizerOptions
import java.io.File
import java.util.Locale

/**
 * Escáner de documentos OFFLINE: cámara -> OCR (ML Kit) -> detecta el idioma ->
 * traduce al otro -> muestra y puede leer en voz alta (con la voz elegida por el usuario).
 * Ventana independiente del traductor de voz.
 */
class DocumentoActivity : AppCompatActivity() {

    private lateinit var binding: ActivityDocumentoBinding
    private var tts: TextToSpeech? = null
    private val vozErik by lazy { VozErik(applicationContext) }

    private lateinit var esEn: Translator   // español -> inglés
    private lateinit var enEs: Translator   // inglés -> español
    private var traductorActual: Translator? = null
    private var modelosListos = false

    private var idiomaVozOrigen: Locale = Locale.forLanguageTag("es-ES")
    private var idiomaVozDestino: Locale = Locale.ENGLISH

    private val ocr by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val langId by lazy { LanguageIdentification.getClient() }
    private var fotoUri: Uri? = null

    private val tomarFoto = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        val uri = fotoUri
        if (ok && uri != null) procesarDocumento(uri) else estado("Escaneo cancelado.")
    }

    // Elegir una foto ya recibida (galería / mensajes).
    private val elegirFoto = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri != null) procesarDocumento(uri) else estado("No elegiste ninguna foto.")
    }

    // Número de contenedor ISO: 4 letras + 7 dígitos (p.ej. EITU1814854).
    private val reContenedor = Regex("\\b([A-Z]{4})\\s?(\\d{7})\\b")
    // Dirección EE.UU.: nº de calle + … + estado (2 letras) + código postal (5 dígitos).
    private val reDireccion = Regex("\\b\\d{2,6}\\s+[A-Za-z0-9 .,'#/-]{3,70}?[A-Z]{2}\\s?\\d{5}\\b")

    // true si la imagen llegó por "Compartir con Erik": tras el OCR, extrae datos solo.
    private var autoExtraer = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityDocumentoBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tts = TextToSpeech(this) { }

        esEn = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.SPANISH)
                .setTargetLanguage(TranslateLanguage.ENGLISH).build()
        )
        enEs = Translation.getClient(
            TranslatorOptions.Builder()
                .setSourceLanguage(TranslateLanguage.ENGLISH)
                .setTargetLanguage(TranslateLanguage.SPANISH).build()
        )

        binding.buttonDocScan.isEnabled = false
        descargarModelos()

        binding.buttonDocScan.setOnClickListener { escanearDocumento() }
        binding.buttonDocGaleria.setOnClickListener { elegirFoto.launch("image/*") }
        binding.buttonLeerDocOriginal.setOnClickListener { leer(binding.textDocOriginal.text, idiomaVozOrigen) }
        binding.buttonLeerDocTraduccion.setOnClickListener { leer(binding.textDocTraduccion.text, idiomaVozDestino) }
        binding.buttonDocExtraer.setOnClickListener { extraerDatos() }
        binding.buttonLeerDocDatos.setOnClickListener { leer(binding.textDocDatos.text, Locale.forLanguageTag("es-ES")) }

        // Si Erik se abrió por "Compartir" una imagen (de Mensajes, galería, WhatsApp…),
        // procesa esa foto y extrae los datos automáticamente.
        if (intent?.action == Intent.ACTION_SEND && intent.type?.startsWith("image/") == true) {
            val uri = IntentCompat.getParcelableExtra(intent, Intent.EXTRA_STREAM, Uri::class.java)
            if (uri != null) { autoExtraer = true; procesarDocumento(uri) }
            else estado("No recibí la imagen compartida, Ariel.")
        }
    }

    /** Extrae del documento de despacho el NÚMERO DE CONTENEDOR y la DIRECCIÓN de entrega. */
    private fun extraerDatos() {
        val texto = binding.textDocOriginal.text?.toString()?.trim().orEmpty()
        if (texto.isBlank() || texto == "—" || texto == "…") {
            estado("Primero escanea o elige la foto del documento, Ariel."); return
        }
        estado("Extrayendo datos del documento…")

        // 1) Contenedor y dirección por patrón exacto: muy fiable, sin modelo.
        val textoMayus = texto.uppercase()
        val contenedorRegex = reContenedor.find(textoMayus)
            ?.let { "${it.groupValues[1]}${it.groupValues[2]}" }
        val direccionRegex = reDireccion.find(textoMayus)?.value?.trim()

        // Si ya tengo ambos por patrón, ni consulto el modelo.
        if (contenedorRegex != null && direccionRegex != null) {
            mostrarDatos("Contenedor: $contenedorRegex\nDirección: $direccionRegex"); return
        }
        // 2) Lo que falte, con el modelo del VPS.
        if (BuildConfig.OLLAMA_URL.isBlank()) {
            mostrarDatos(
                "Contenedor: ${contenedorRegex ?: "no encontrado"}\n" +
                "Dirección: ${direccionRegex ?: "(necesito el asistente del VPS)"}"
            ); return
        }
        val prompt = """
El siguiente es el texto (OCR) de un documento de despacho de contenedores.
IMPORTANTE: básate SOLO en el texto impreso o mecanografiado. IGNORA por completo
las anotaciones escritas a mano, los resaltados, los sellos y las notas añadidas.
Extrae SOLO estos dos datos y devuélvelos EXACTAMENTE en dos líneas con este formato:
Contenedor: <número de contenedor impreso: 4 letras seguidas de 7 dígitos>
Dirección: <dirección de entrega impresa tras "DEL:": calle, ciudad, estado y código postal, sin el nombre de la empresa ni el teléfono>
Si un dato no aparece en el texto impreso, escribe "no encontrado". No añadas nada más.

TEXTO:
$texto
""".trim()
        Thread {
            val resp = try { consultarModelo(prompt) } catch (e: Exception) { null }
            runOnUiThread {
                val contenedor = contenedorRegex ?: extraerCampo(resp, "Contenedor") ?: "no encontrado"
                val direccion = direccionRegex ?: extraerCampo(resp, "Dirección")
                    ?: extraerCampo(resp, "Direccion") ?: "no encontrada"
                mostrarDatos("Contenedor: $contenedor\nDirección: $direccion")
            }
        }.start()
    }

    /** Busca en la respuesta del modelo una línea "Campo: valor" y devuelve el valor. */
    private fun extraerCampo(resp: String?, campo: String): String? {
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

    private fun mostrarDatos(texto: String) {
        binding.textDocDatos.text = texto
        binding.cardDocDatos.visibility = View.VISIBLE
        estado("Datos extraídos, Ariel.")
        leer(texto, Locale.forLanguageTag("es-ES"))
    }

    /** Consulta al modelo del VPS (Ollama) y devuelve la respuesta, o null si falla. */
    private fun consultarModelo(prompt: String): String? {
        val body = JSONObject().apply {
            put("model", BuildConfig.OLLAMA_MODEL)
            put("prompt", prompt)
            put("stream", false)
            put("keep_alive", "30m")
            put("options", JSONObject().put("num_predict", 300))
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

    private fun descargarModelos() {
        estado("Descargando idiomas (~30 MB, una sola vez)…")
        val cond = DownloadConditions.Builder().build()
        esEn.downloadModelIfNeeded(cond).addOnSuccessListener {
            enEs.downloadModelIfNeeded(cond).addOnSuccessListener {
                modelosListos = true
                binding.buttonDocScan.isEnabled = true
                estado("Listo. Escanea un documento.")
            }.addOnFailureListener { estado("No pude descargar el idioma. Revisa tu conexión.") }
        }.addOnFailureListener { estado("No pude descargar el idioma. Revisa tu conexión.") }
    }

    private fun escanearDocumento() {
        if (!modelosListos) { estado("Aún preparando los idiomas…"); return }
        try {
            val f = File(cacheDir, "documento.jpg")
            val uri = FileProvider.getUriForFile(this, "$packageName.fileprovider", f)
            fotoUri = uri
            estado("Enfoca el documento y toma la foto…")
            tomarFoto.launch(uri)
        } catch (e: Exception) {
            estado("No pude abrir la cámara.")
        }
    }

    private fun procesarDocumento(uri: Uri) {
        estado("Leyendo el documento…")
        try {
            val imagen = InputImage.fromFilePath(this, uri)
            ocr.process(imagen)
                .addOnSuccessListener { visionText ->
                    val texto = visionText.text.replace("\n", " ").replace(Regex("\\s+"), " ").trim()
                    if (texto.isBlank()) { estado("No detecté texto en la foto. Prueba con más luz."); return@addOnSuccessListener }
                    binding.textDocOriginal.text = texto
                    // Foto compartida: extrae contenedor + dirección directamente (sin traducir).
                    if (autoExtraer) { autoExtraer = false; extraerDatos(); return@addOnSuccessListener }
                    estado("Traduciendo…")
                    langId.identifyLanguage(texto)
                        .addOnSuccessListener { codigo ->
                            if (codigo == "es") {
                                traductorActual = esEn
                                idiomaVozOrigen = Locale.forLanguageTag("es-ES"); idiomaVozDestino = Locale.ENGLISH
                            } else {
                                traductorActual = enEs
                                idiomaVozOrigen = Locale.ENGLISH; idiomaVozDestino = Locale.forLanguageTag("es-ES")
                            }
                            traducir(texto)
                        }
                        .addOnFailureListener {
                            traductorActual = enEs
                            idiomaVozOrigen = Locale.ENGLISH; idiomaVozDestino = Locale.forLanguageTag("es-ES")
                            traducir(texto)
                        }
                }
                .addOnFailureListener { estado("No pude leer el documento.") }
        } catch (e: Exception) {
            estado("No pude abrir la foto.")
        }
    }

    private fun traducir(texto: String) {
        val traductor = traductorActual ?: return
        traductor.translate(texto)
            .addOnSuccessListener { traducido ->
                binding.textDocTraduccion.text = traducido
                estado("Listo. Escanea otro documento o toca Leer.")
                leer(traducido, idiomaVozDestino)
            }
            .addOnFailureListener { estado("No pude traducir. Inténtalo otra vez.") }
    }

    /** Lee un texto con la voz elegida por el usuario; respaldo: TTS de Android. */
    private fun leer(texto: CharSequence?, idioma: Locale) {
        val t = texto?.toString()?.trim().orEmpty()
        if (t.isBlank() || t == "—" || t == "…") { estado("Aún no hay texto para leer."); return }
        val codigo = if (idioma.language == "en") "en" else "es"
        vozErik.hablar(t, codigo, null) { txt, _ ->
            tts?.language = idioma
            tts?.speak(txt, TextToSpeech.QUEUE_FLUSH, null, "leer")
        }
    }

    private fun estado(texto: String) {
        binding.textDocEstado.text = texto
    }

    override fun onDestroy() {
        super.onDestroy()
        vozErik.liberar()
        tts?.stop(); tts?.shutdown(); tts = null
        esEn.close(); enEs.close()
        ocr.close(); langId.close()
    }
}
