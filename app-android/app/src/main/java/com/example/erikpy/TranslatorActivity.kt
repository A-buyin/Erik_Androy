package com.example.erikpy

import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.example.erikpy.databinding.ActivityTranslatorBinding
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
 * Traductor de voz bidireccional OFFLINE (ML Kit).
 *  - Botón 1: hablas español -> se traduce y se lee en INGLÉS.
 *  - Botón 2: te hablan inglés -> se traduce y se lee en ESPAÑOL.
 *
 * Reconocimiento: Google STT (por idioma). Traducción: ML Kit en el dispositivo.
 * Salida: TextToSpeech en el idioma destino. No manda audio a ningún servidor.
 */
class TranslatorActivity : AppCompatActivity() {

    private lateinit var binding: ActivityTranslatorBinding
    private var tts: TextToSpeech? = null
    private var recognizer: SpeechRecognizer? = null
    private val vozErik by lazy { VozErik(applicationContext) }

    private lateinit var esEn: Translator   // español -> inglés
    private lateinit var enEs: Translator   // inglés -> español

    private var modelosListos = false

    // Dirección activa mientras se escucha.
    private var traductorActual: Translator? = null
    private var idiomaVozDestino: Locale = Locale.ENGLISH
    private var idiomaVozOrigen: Locale = Locale.forLanguageTag("es-ES")
    private var idiomaOrigenStt: String = "es-ES"

    // OCR e identificación de idioma (offline).
    private val ocr by lazy { TextRecognition.getClient(TextRecognizerOptions.DEFAULT_OPTIONS) }
    private val langId by lazy { LanguageIdentification.getClient() }
    private var fotoUri: Uri? = null

    private val pedirMicrofono = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { concedido ->
        if (!concedido) estado("Necesito permiso de micrófono para traducir.")
    }

    private val tomarFoto = registerForActivityResult(
        ActivityResultContracts.TakePicture()
    ) { ok ->
        val uri = fotoUri
        if (ok && uri != null) procesarDocumento(uri) else estado("Escaneo cancelado.")
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityTranslatorBinding.inflate(layoutInflater)
        setContentView(binding.root)

        tts = TextToSpeech(this) { }

        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) pedirMicrofono.launch(Manifest.permission.RECORD_AUDIO)

        // Traductores ML Kit (offline).
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

        binding.buttonEsEn.isEnabled = false
        binding.buttonEnEs.isEnabled = false
        descargarModelos()

        binding.buttonEsEn.setOnClickListener {
            escuchar("es-ES", esEn, Locale.ENGLISH)
        }
        binding.buttonEnEs.setOnClickListener {
            escuchar("en-US", enEs, Locale.forLanguageTag("es-ES"))
        }
        binding.buttonScan.setOnClickListener { escanearDocumento() }

        binding.buttonLeerOriginal.setOnClickListener { leer(binding.textOriginal.text, idiomaVozOrigen) }
        binding.buttonLeerTraduccion.setOnClickListener { leer(binding.textTraduccion.text, idiomaVozDestino) }
    }

    /** Lee un texto en voz alta con la voz elegida por el usuario; respaldo: TTS de Android. */
    private fun leer(texto: CharSequence?, idioma: Locale) {
        val t = texto?.toString()?.trim().orEmpty()
        if (t.isBlank() || t == "—" || t == "…") { estado("Aún no hay texto para leer."); return }
        val codigo = if (idioma.language == "en") "en" else "es"
        vozErik.hablar(t, codigo, null) { txt, _ ->
            tts?.language = idioma
            tts?.speak(txt, TextToSpeech.QUEUE_FLUSH, null, "leer")
        }
    }

    // --- Escaneo de documentos: cámara -> OCR -> traducir -> leer en voz alta ---

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
                    binding.textOriginal.text = texto
                    estado("Traduciendo…")
                    // Detecta el idioma del documento y traduce al otro.
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

    /** Descarga (una sola vez) los modelos de idioma offline. */
    private fun descargarModelos() {
        estado("Descargando idiomas (~30 MB, una sola vez)…")
        val cond = DownloadConditions.Builder().build()
        esEn.downloadModelIfNeeded(cond).addOnSuccessListener {
            enEs.downloadModelIfNeeded(cond).addOnSuccessListener {
                modelosListos = true
                binding.buttonEsEn.isEnabled = true
                binding.buttonEnEs.isEnabled = true
                estado("Listo. Toca un botón y habla.")
            }.addOnFailureListener { estado("No pude descargar el idioma. Revisa tu conexión.") }
        }.addOnFailureListener { estado("No pude descargar el idioma. Revisa tu conexión.") }
    }

    /** Escucha en [idiomaOrigen], y al reconocer traduce con [traductor] y lo lee en [vozDestino]. */
    private fun escuchar(idiomaOrigen: String, traductor: Translator, vozDestino: Locale) {
        if (!modelosListos) { estado("Aún preparando los idiomas…"); return }
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) { pedirMicrofono.launch(Manifest.permission.RECORD_AUDIO); return }

        traductorActual = traductor
        idiomaVozDestino = vozDestino
        idiomaVozOrigen = Locale.forLanguageTag(idiomaOrigen)
        idiomaOrigenStt = idiomaOrigen
        binding.textOriginal.text = "…"
        binding.textTraduccion.text = "…"
        estado(if (idiomaOrigen.startsWith("es")) "Escuchando en español…" else "Escuchando en inglés…")

        recognizer?.destroy()
        recognizer = SpeechRecognizer.createSpeechRecognizer(this).apply {
            setRecognitionListener(oyente)
            startListening(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
                putExtra(RecognizerIntent.EXTRA_LANGUAGE, idiomaOrigen)
                putExtra(RecognizerIntent.EXTRA_MAX_RESULTS, 1)
            })
        }
    }

    private val oyente = object : android.speech.RecognitionListener {
        override fun onResults(results: Bundle?) {
            val texto = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                ?.firstOrNull()?.trim().orEmpty()
            if (texto.isBlank()) { estado("No te entendí, inténtalo otra vez."); return }
            binding.textOriginal.text = texto
            estado("Traduciendo…")
            traducir(texto)
        }
        override fun onError(error: Int) {
            estado(
                if (error == SpeechRecognizer.ERROR_NO_MATCH ||
                    error == SpeechRecognizer.ERROR_SPEECH_TIMEOUT
                ) "No te entendí, toca de nuevo." else "Error de reconocimiento, inténtalo otra vez."
            )
        }
        override fun onReadyForSpeech(params: Bundle?) {}
        override fun onBeginningOfSpeech() {}
        override fun onRmsChanged(rmsdB: Float) {}
        override fun onBufferReceived(buffer: ByteArray?) {}
        override fun onEndOfSpeech() {}
        override fun onPartialResults(partialResults: Bundle?) {}
        override fun onEvent(eventType: Int, params: Bundle?) {}
    }

    private fun traducir(texto: String) {
        val traductor = traductorActual ?: return
        traductor.translate(texto)
            .addOnSuccessListener { traducido ->
                binding.textTraduccion.text = traducido
                estado("Listo. Toca un botón y habla.")
                leer(traducido, idiomaVozDestino)   // voz elegida (con respaldo a TTS de Android)
            }
            .addOnFailureListener { estado("No pude traducir. Inténtalo otra vez.") }
    }

    private fun estado(texto: String) {
        binding.textEstado.text = texto
    }

    override fun onDestroy() {
        super.onDestroy()
        recognizer?.destroy(); recognizer = null
        vozErik.liberar()
        tts?.stop(); tts?.shutdown(); tts = null
        esEn.close(); enEs.close()
        ocr.close(); langId.close()
    }
}
