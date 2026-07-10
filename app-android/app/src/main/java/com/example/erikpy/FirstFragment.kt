package com.example.erikpy

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.ContactsContract
import android.provider.Telephony
import android.speech.RecognizerIntent
import android.speech.tts.TextToSpeech
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.activity.result.contract.ActivityResultContracts
import com.example.erikpy.databinding.FragmentFirstBinding
import java.util.Locale

class FirstFragment : Fragment() {

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!

    private var tts: TextToSpeech? = null
    private var pendingNumber: String? = null

    // --- Emparejador tolerante de comandos (portado de la versión Python Erik.py) ---
    // Tolera acentos, mayúsculas y errores típicos de transcripción de voz.
    private val patronLlamada = Regex(
        "^\\s*(?:llama(?:r|da)?|lama|yama|marca(?:r)?|telefonea(?:r)?)\\s+(?:al?\\s+)?(.+)$",
        RegexOption.IGNORE_CASE
    )
    private val patronUltimoMsj = Regex("\\bultim\\w*\\s+(?:mensaje|mensajes|sms)")
    private val patronDeep1 = Regex(
        "\\bejecut\\w*\\s+(?:(?:el|la|un|una|los|las)\\s+)?(?:deep|dip|dep|dib|the)\\s+(?:uno|1|primero)\\b"
    )
    private val patronDeep2 = Regex(
        "\\bejecut\\w*\\s+(?:(?:el|la|un|una|los|las)\\s+)?(?:deep|dip|dep|dib|the)\\s+(?:dos|2|segundo)\\b"
    )
    private val patronEscaneo = Regex(
        "\\bejecut\\w*\\s+(?:(?:el|la|un|una|los|las)\\s+)?escan\\w*"
    )
    private val patronOllama = Regex(
        "(?:pregunt\\w*|consult\\w*)\\s+a\\s+(?:o\\s*llama|ollama|olama|oyama|llama)\\b\\s*(.*)$"
    )

    /** Minúsculas, sin acentos y espacios colapsados, para comparar comandos
     *  tolerando errores de transcripción de voz. */
    private fun normalizar(texto: String): String {
        val nfd = java.text.Normalizer.normalize(texto, java.text.Normalizer.Form.NFD)
        val sinAcentos = nfd.replace(Regex("\\p{Mn}+"), "")
        return sinAcentos.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
    }

    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val denied = result.filterValues { !it }.keys
        if (denied.isNotEmpty()) {
            respond("Faltan permisos: ${denied.joinToString()}")
        }
    }

    private val requestCallPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        val number = pendingNumber
        pendingNumber = null
        if (granted && number != null) {
            placeCall(number)
        } else if (!granted) {
            respond("Sin permiso para llamar.")
        }
    }

    // Entrada por voz: usa el reconocedor de Android (diálogo de dictado) y pasa
    // el texto reconocido al mismo procesador de comandos que la entrada de texto.
    private val speechRecognizer = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            val matches = result.data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
            val spoken = matches?.firstOrNull()?.trim()
            if (!spoken.isNullOrEmpty()) {
                binding.inputCommand.setText(spoken)
                process(spoken)
            } else {
                respond("No entendí. Intenta de nuevo.")
            }
        }
    }

    private fun startVoiceInput() {
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(
                RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                RecognizerIntent.LANGUAGE_MODEL_FREE_FORM
            )
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, "es-ES")
            putExtra(RecognizerIntent.EXTRA_PROMPT, getString(R.string.voice_prompt))
        }
        try {
            speechRecognizer.launch(intent)
        } catch (e: ActivityNotFoundException) {
            respond("El reconocimiento de voz no está disponible en este dispositivo.")
        }
    }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tts = TextToSpeech(requireContext()) { status ->
            if (status == TextToSpeech.SUCCESS) {
                tts?.language = Locale.forLanguageTag("es-ES")
            }
        }

        requestPermissions.launch(
            arrayOf(Manifest.permission.READ_SMS, Manifest.permission.READ_CONTACTS)
        )

        binding.buttonSend.setOnClickListener {
            val cmd = binding.inputCommand.text.toString().trim()
            if (cmd.isNotEmpty()) {
                process(cmd)
                binding.inputCommand.text?.clear()
            }
        }

        binding.buttonVoice.setOnClickListener {
            startVoiceInput()
        }

        // Mostrar / ocultar la lista de comandos (oculta por defecto).
        binding.buttonToggleHelp.setOnClickListener {
            val visible = binding.cardHelp.visibility == View.VISIBLE
            binding.cardHelp.visibility = if (visible) View.GONE else View.VISIBLE
            binding.buttonToggleHelp.setText(
                if (visible) R.string.show_commands else R.string.hide_commands
            )
            binding.buttonToggleHelp.setIconResource(
                if (visible) R.drawable.ic_expand_more else R.drawable.ic_expand_less
            )
        }
    }

    private fun process(command: String) {
        val cmdLower = command.trim().lowercase(Locale.ROOT)   // conserva acentos (para nombres de contacto)
        val cmdNorm = normalizar(command)                      // sin acentos (para comparar comandos)

        // AYUDA
        if (cmdNorm == "ayuda" || cmdNorm == "help" || cmdNorm == "?" ||
            cmdNorm.startsWith("ayuda ") || cmdNorm.startsWith("help ")
        ) {
            respond(getString(R.string.help_text))
            return
        }

        // LIMPIAR
        if (cmdNorm in listOf("clear", "cls", "limpiar", "limpia")) {
            respond(getString(R.string.errik_ready))
            return
        }

        // SALIR
        if (cmdNorm.contains("salir") || cmdNorm.contains("apagate")) {
            respond("Cerrando Errik...")
            requireActivity().finish()
            return
        }

        // LLAMADA (tolerante: llama / llamar / llamada / marca / telefonea, con o sin "a"/"al")
        val mLlamada = patronLlamada.find(cmdLower)
        if (mLlamada != null) {
            val target = mLlamada.groupValues[1].trim().trim { it in ".,;:!?¿¡ " }
            if (target.isEmpty()) {
                respond("¿A quién quieres llamar?")
                return
            }
            if (target.matches(Regex("^[+0-9 \\-]+$"))) {
                tryCall(target.replace(" ", ""))
            } else {
                val contacto = lookupContact(target)
                if (contacto != null) {
                    respond("Llamando a ${contacto.nombre}")
                    tryCall(contacto.numero)
                } else {
                    respond("No encontré a $target en contactos.")
                }
            }
            return
        }

        // ÚLTIMO MENSAJE (últim* mensaje/mensajes/sms)
        if (patronUltimoMsj.containsMatchIn(cmdNorm)) {
            readLastSms()
            return
        }

        // Comandos solo de la versión PC/Termux (IDS y LLM local): se reconocen
        // igual que en Erik.py, pero se informa que no aplican en la app móvil.
        when {
            patronDeep1.containsMatchIn(cmdNorm) ->
                respond("El modelo Deep 1 solo está disponible en la versión de PC/Termux.")
            patronDeep2.containsMatchIn(cmdNorm) ->
                respond("El modelo Deep 2 solo está disponible en la versión de PC/Termux.")
            patronEscaneo.containsMatchIn(cmdNorm) ->
                respond("El escaneo IDS solo está disponible en la versión de PC/Termux.")
            patronOllama.containsMatchIn(cmdNorm) ->
                respond("Ollama solo está disponible en la versión de PC/Termux.")
            else ->
                respond("Comando no reconocido. Prueba: llama a [nombre], último mensaje o ayuda")
        }
    }

    /** Contacto encontrado en la agenda: nombre real + número marcable. */
    private data class Contacto(val nombre: String, val numero: String)

    /**
     * Busca en la agenda el contacto cuyo nombre más se parece a lo que dijo el
     * usuario, tolerando acentos y errores de transcripción de voz (igual que la
     * versión Python). Recorre TODOS los contactos y devuelve el mejor por
     * puntuación de similitud, no la primera coincidencia. null si no hay ninguno
     * lo bastante parecido o si falta el permiso de contactos.
     */
    private fun lookupContact(name: String, umbral: Double = 0.62): Contacto? {
        if (ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.READ_CONTACTS
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
        requireContext().contentResolver.query(uri, projection, null, null, null)?.use { c ->
            val idxNombre = 0
            val idxNumero = 1
            while (c.moveToNext()) {
                val nombre = c.getString(idxNombre) ?: continue
                val numero = c.getString(idxNumero) ?: continue
                val nombreNorm = normalizar(nombre)
                if (nombreNorm.isEmpty()) continue

                // Similitud del nombre completo.
                var score = ratio(obj, nombreNorm)

                // Coincidencia con cada palabra suelta (nombre de pila o apellido).
                for (parte in nombreNorm.split(" ")) {
                    if (parte == obj) {
                        score = maxOf(score, 0.98)
                    } else {
                        score = maxOf(score, ratio(obj, parte) * 0.85)
                    }
                }

                // El objetivo aparece dentro del nombre (o al revés).
                if (nombreNorm.contains(obj) || obj.contains(nombreNorm)) {
                    score = maxOf(score, 0.9)
                }

                if (score > mejorScore) {
                    mejorScore = score
                    mejor = Contacto(nombre, numero)
                }
            }
        }
        return if (mejor != null && mejorScore >= umbral) mejor else null
    }

    /**
     * Similitud entre dos cadenas en [0,1] basada en la distancia de
     * Levenshtein: 1 - distancia / longitud_máxima. Equivale, para nuestro uso,
     * al SequenceMatcher.ratio() de la versión Python.
     */
    private fun ratio(a: String, b: String): Double {
        if (a.isEmpty() && b.isEmpty()) return 1.0
        val maxLen = maxOf(a.length, b.length)
        if (maxLen == 0) return 1.0
        return 1.0 - levenshtein(a, b).toDouble() / maxLen
    }

    private fun levenshtein(a: String, b: String): Int {
        val dp = IntArray(b.length + 1) { it }
        for (i in 1..a.length) {
            var prev = dp[0]
            dp[0] = i
            for (j in 1..b.length) {
                val tmp = dp[j]
                val costo = if (a[i - 1] == b[j - 1]) 0 else 1
                dp[j] = minOf(dp[j] + 1, dp[j - 1] + 1, prev + costo)
                prev = tmp
            }
        }
        return dp[b.length]
    }

    private fun readLastSms() {
        if (ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.READ_SMS
            ) != PackageManager.PERMISSION_GRANTED
        ) {
            respond("Sin permiso para leer mensajes.")
            return
        }

        val projection = arrayOf(
            Telephony.Sms.ADDRESS,
            Telephony.Sms.BODY
        )
        requireContext().contentResolver.query(
            Telephony.Sms.Inbox.CONTENT_URI,
            projection,
            null, null,
            "${Telephony.Sms.DATE} DESC"
        )?.use { c ->
            if (c.moveToFirst()) {
                val from = c.getString(0) ?: "desconocido"
                val body = c.getString(1) ?: ""
                respond("El último mensaje es de $from y dice: $body")
                return
            }
        }
        respond("No hay mensajes en la bandeja de entrada.")
    }

    private fun tryCall(number: String) {
        if (ContextCompat.checkSelfPermission(
                requireContext(), Manifest.permission.CALL_PHONE
            ) == PackageManager.PERMISSION_GRANTED
        ) {
            placeCall(number)
        } else {
            pendingNumber = number
            requestCallPermission.launch(Manifest.permission.CALL_PHONE)
        }
    }

    private fun placeCall(number: String) {
        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$number"))
        startActivity(intent)
    }

    private fun respond(text: String) {
        binding.textviewResponse.text = text
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "errik")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        tts?.stop()
        tts?.shutdown()
        tts = null
        _binding = null
    }
}
