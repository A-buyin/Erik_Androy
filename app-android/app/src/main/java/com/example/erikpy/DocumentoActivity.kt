package com.example.erikpy

import android.net.Uri
import android.os.Bundle
import android.speech.tts.TextToSpeech
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
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
        binding.buttonLeerDocOriginal.setOnClickListener { leer(binding.textDocOriginal.text, idiomaVozOrigen) }
        binding.buttonLeerDocTraduccion.setOnClickListener { leer(binding.textDocTraduccion.text, idiomaVozDestino) }
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
