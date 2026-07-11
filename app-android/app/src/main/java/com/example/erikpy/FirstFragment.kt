package com.example.erikpy

import android.Manifest
import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.ContactsContract
import android.speech.RecognizerIntent
import android.telecom.TelecomManager
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.activity.result.contract.ActivityResultContracts
import com.example.erikpy.databinding.FragmentFirstBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.Locale

class FirstFragment : Fragment() {

    private var _binding: FragmentFirstBinding? = null
    private val binding get() = _binding!!

    private var tts: TextToSpeech? = null

    // Cerebro compartido (mismas acciones que el servicio de escucha permanente).
    private val commandHandler by lazy {
        CommandHandler(requireContext().applicationContext) { texto -> respond(texto) }
    }

    // Patrones que se resuelven en el propio fragmento (los demás los ve CommandHandler).
    private val patronSaludo = Regex(
        "^\\s*(?:(?:hola|ola|oye|hey|ey)\\s+)?(?:erik|eric|erick|herik|érik)\\s*$"
    )
    private val patronDeep1 = Regex(
        "\\bejecut\\w*\\s+(?:(?:el|la|un|una|los|las)\\s+)?(?:deep|dip|dep|dib|the)\\s+(?:uno|1|primero)\\b"
    )
    private val patronDeep2 = Regex(
        "\\bejecut\\w*\\s+(?:(?:el|la|un|una|los|las)\\s+)?(?:deep|dip|dep|dib|the)\\s+(?:dos|2|segundo)\\b"
    )
    private val patronEscaneo = Regex(
        "\\bejecut\\w*\\s+(?:(?:el|la|un|una|los|las)\\s+)?escan\\w*"
    )

    private fun normalizar(texto: String): String {
        val nfd = java.text.Normalizer.normalize(texto, java.text.Normalizer.Form.NFD)
        val sinAcentos = nfd.replace(Regex("\\p{Mn}+"), "")
        return sinAcentos.lowercase(Locale.ROOT).replace(Regex("\\s+"), " ").trim()
    }

    // Permisos base (llamar, colgar, contactos, SMS) pedidos al abrir la app.
    private val requestPermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { /* si el usuario niega alguno, se avisará al usarlo */ }

    // Permiso de contactos pedido al pulsar el botón "Contactos del teléfono".
    private val requestContactsPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) mostrarContactos()
        else respond("Necesito permiso de contactos para mostrarte la agenda, Ariel.")
    }

    // Permisos para la escucha permanente (micrófono + notificación).
    private val requestWakePermissions = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { result ->
        val micOk = result[Manifest.permission.RECORD_AUDIO] ?: hasPermission(Manifest.permission.RECORD_AUDIO)
        if (micOk) {
            startWakeService()
        } else {
            binding.switchWake.isChecked = false
            respond("Necesito permiso de micrófono para escucharte, Ariel.")
        }
    }

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
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
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
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentFirstBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        tts = TextToSpeech(requireContext()) { status ->
            if (status == TextToSpeech.SUCCESS) tts?.language = Locale.forLanguageTag("es-ES")
        }

        requestPermissions.launch(
            arrayOf(
                Manifest.permission.READ_SMS,
                Manifest.permission.READ_CONTACTS,
                Manifest.permission.CALL_PHONE,
                Manifest.permission.ANSWER_PHONE_CALLS
            )
        )

        binding.buttonSend.setOnClickListener {
            val cmd = binding.inputCommand.text.toString().trim()
            if (cmd.isNotEmpty()) {
                process(cmd)
                binding.inputCommand.text?.clear()
            }
        }

        binding.buttonVoice.setOnClickListener { startVoiceInput() }

        binding.buttonTranslator.setOnClickListener {
            startActivity(Intent(requireContext(), TranslatorActivity::class.java))
        }

        binding.buttonContacts.setOnClickListener {
            if (hasPermission(Manifest.permission.READ_CONTACTS)) mostrarContactos()
            else requestContactsPermission.launch(Manifest.permission.READ_CONTACTS)
        }

        // Interruptor de escucha permanente ("hola Erik").
        binding.switchWake.isChecked = false
        binding.switchWake.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) enableWakeListening() else stopWakeService()
        }

        binding.buttonToggleHelp.setOnClickListener {
            val visible = binding.cardHelp.visibility == View.VISIBLE
            binding.cardHelp.visibility = if (visible) View.GONE else View.VISIBLE
            binding.buttonToggleHelp.setText(if (visible) R.string.show_commands else R.string.hide_commands)
            binding.buttonToggleHelp.setIconResource(
                if (visible) R.drawable.ic_expand_more else R.drawable.ic_expand_less
            )
        }
    }

    private fun process(command: String) {
        android.util.Log.i("ErikVoz", "Comando recibido: $command")
        val cmdNorm = normalizar(command)

        // AYUDA
        if (cmdNorm == "ayuda" || cmdNorm == "help" || cmdNorm == "?" ||
            cmdNorm.startsWith("ayuda ") || cmdNorm.startsWith("help ")
        ) { respond(getString(R.string.help_text)); return }

        // LIMPIAR
        if (cmdNorm in listOf("clear", "cls", "limpiar", "limpia")) {
            respond(getString(R.string.errik_ready)); return
        }

        // SALIR
        if (cmdNorm.contains("salir") || cmdNorm.contains("apagate")) {
            respond("Cerrando Errik..."); requireActivity().finish(); return
        }

        // SALUDO DE ACTIVACIÓN ("hola Erik") -> saluda y vuelve a escuchar.
        if (patronSaludo.matches(cmdNorm)) {
            respondThenListen("Hola Ariel, ¿en qué te puedo ayudar?"); return
        }

        // Modelos IDS: solo existen en la versión PC/Termux.
        when {
            patronDeep1.containsMatchIn(cmdNorm) ->
                respond("El modelo Deep 1 solo está disponible en la versión de PC/Termux.")
            patronDeep2.containsMatchIn(cmdNorm) ->
                respond("El modelo Deep 2 solo está disponible en la versión de PC/Termux.")
            patronEscaneo.containsMatchIn(cmdNorm) ->
                respond("El escaneo IDS solo está disponible en la versión de PC/Termux.")
            // Todo lo demás (llamar, colgar, último mensaje, preguntas): al cerebro.
            else -> commandHandler.handle(command)
        }
    }

    // --- Escucha permanente ---

    private fun hasPermission(perm: String) =
        ContextCompat.checkSelfPermission(requireContext(), perm) == PackageManager.PERMISSION_GRANTED

    private fun enableWakeListening() {
        val faltan = mutableListOf<String>()
        if (!hasPermission(Manifest.permission.RECORD_AUDIO)) faltan.add(Manifest.permission.RECORD_AUDIO)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            !hasPermission(Manifest.permission.POST_NOTIFICATIONS)
        ) faltan.add(Manifest.permission.POST_NOTIFICATIONS)

        if (faltan.isEmpty()) startWakeService()
        else requestWakePermissions.launch(faltan.toTypedArray())
    }

    private fun startWakeService() {
        val intent = Intent(requireContext(), WakeWordService::class.java)
        ContextCompat.startForegroundService(requireContext(), intent)
        // Solo en pantalla, SIN voz: si se dijera en voz alta, el servicio se
        // oiría a sí mismo ("...di hola Erik...") y se activaría en falso.
        mostrar("Escucha permanente activada. Di \"hola Erik\" cuando quieras, Ariel.")
    }

    private fun stopWakeService() {
        requireContext().stopService(Intent(requireContext(), WakeWordService::class.java))
        mostrar("Escucha permanente desactivada, Ariel.")
    }

    /** Muestra un texto en pantalla SIN leerlo en voz alta. */
    private fun mostrar(text: String) {
        _binding?.textviewResponse?.text = text
    }

    // --- Contactos del teléfono ---

    private data class ContactoAgenda(val nombre: String, val numero: String)

    /** Lee TODOS los contactos en segundo plano y los muestra en una lista. */
    private fun mostrarContactos() {
        mostrar("Cargando contactos, Ariel...")
        Thread {
            val lista = leerTodosLosContactos()
            activity?.runOnUiThread {
                if (!isAdded || _binding == null) return@runOnUiThread
                if (lista.isEmpty()) {
                    respond("No encontré contactos en el teléfono, Ariel.")
                } else {
                    mostrar("Tienes ${lista.size} contactos, Ariel.")
                    mostrarDialogoContactos(lista)
                }
            }
        }.start()
    }

    /** Consulta la agenda (nombre + número), ordenada y sin duplicados. */
    private fun leerTodosLosContactos(): List<ContactoAgenda> {
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val orden = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC"
        val vistos = HashSet<String>()
        val lista = ArrayList<ContactoAgenda>()
        requireContext().contentResolver.query(uri, projection, null, null, orden)?.use { c ->
            while (c.moveToNext()) {
                val nombre = c.getString(0) ?: continue
                val numero = c.getString(1) ?: continue
                // Evita repetir el mismo nombre+número (varias cuentas sincronizadas).
                val clave = "$nombre|${numero.filter { it.isDigit() }}"
                if (vistos.add(clave)) lista.add(ContactoAgenda(nombre, numero))
            }
        }
        return lista
    }

    /** Diálogo con la lista de contactos; al tocar uno, llama a ese contacto. */
    private fun mostrarDialogoContactos(contactos: List<ContactoAgenda>) {
        val items = contactos.map { "${it.nombre}\n${it.numero}" }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Contactos (${contactos.size})")
            .setItems(items) { _, which ->
                val c = contactos[which]
                mostrar("Llamando a ${c.nombre}...")
                llamarContacto(c.numero)
            }
            .setPositiveButton("Cerrar", null)
            .show()
    }

    /** Realiza la llamada al número dado (igual que CommandHandler.placeCall). */
    @android.annotation.SuppressLint("MissingPermission")
    private fun llamarContacto(numero: String) {
        if (!hasPermission(Manifest.permission.CALL_PHONE)) {
            respond("No tengo permiso para llamar, Ariel.")
            return
        }
        val uri = Uri.fromParts("tel", numero, null)
        val tm = requireContext().getSystemService(TelecomManager::class.java)
        try {
            tm?.placeCall(uri, null)
        } catch (e: Exception) {
            // Respaldo: intent de llamada con la app en primer plano.
            try {
                startActivity(Intent(Intent.ACTION_CALL, Uri.parse("tel:$numero")))
            } catch (e2: Exception) {
                respond("No pude iniciar la llamada, Ariel.")
            }
        }
    }

    // --- Respuesta por voz / pantalla ---

    private fun respond(text: String) {
        android.util.Log.i("ErikVoz", "Erik responde: $text")
        _binding?.textviewResponse?.text = text
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "errik")
    }

    /** Locuta y, al terminar, reabre el micrófono (modo conversación tras "hola Erik"). */
    private fun respondThenListen(text: String) {
        android.util.Log.i("ErikVoz", "Erik responde: $text")
        _binding?.textviewResponse?.text = text
        tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {}
            override fun onError(utteranceId: String?) {}
            override fun onDone(utteranceId: String?) {
                if (utteranceId == "saludo") activity?.runOnUiThread {
                    if (isAdded && _binding != null) startVoiceInput()
                }
            }
        })
        tts?.speak(text, TextToSpeech.QUEUE_FLUSH, null, "saludo")
    }

    override fun onDestroyView() {
        super.onDestroyView()
        tts?.stop(); tts?.shutdown(); tts = null
        _binding = null
    }
}
