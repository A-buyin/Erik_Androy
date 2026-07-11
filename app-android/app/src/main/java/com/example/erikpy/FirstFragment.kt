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
import androidx.core.widget.addTextChangedListener
import androidx.fragment.app.Fragment
import androidx.activity.result.contract.ActivityResultContracts
import com.example.erikpy.databinding.FragmentFirstBinding
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.io.File
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

    // Permiso de micrófono pedido al pulsar "Grabar mi voz".
    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) iniciarGrabacionVoz()
        else respond("Necesito permiso de micrófono para grabar tu voz, Ariel.")
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

        binding.buttonGrabarVoz.setOnClickListener {
            if (grabandoVoz) detenerGrabacionVoz()
            else if (hasPermission(Manifest.permission.RECORD_AUDIO)) iniciarGrabacionVoz()
            else requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }

        binding.buttonMisGrabaciones.setOnClickListener { mostrarGrabaciones() }

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

    private var dialogoContactos: androidx.appcompat.app.AlertDialog? = null

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
    private fun leerTodosLosContactos(): List<ContactosAdapter.Contacto> {
        val uri = ContactsContract.CommonDataKinds.Phone.CONTENT_URI
        val projection = arrayOf(
            ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME,
            ContactsContract.CommonDataKinds.Phone.NUMBER
        )
        val orden = "${ContactsContract.CommonDataKinds.Phone.DISPLAY_NAME} COLLATE NOCASE ASC"
        val vistos = HashSet<String>()
        val lista = ArrayList<ContactosAdapter.Contacto>()
        requireContext().contentResolver.query(uri, projection, null, null, orden)?.use { c ->
            while (c.moveToNext()) {
                val nombre = c.getString(0) ?: continue
                val numero = c.getString(1) ?: continue
                // Evita repetir el mismo nombre+número (varias cuentas sincronizadas).
                val clave = "$nombre|${numero.filter { it.isDigit() }}"
                if (vistos.add(clave)) lista.add(ContactosAdapter.Contacto(nombre, numero))
            }
        }
        return lista
    }

    /** Diálogo con lista profesional (buscador + avatares); al tocar uno, llama. */
    private fun mostrarDialogoContactos(contactos: List<ContactosAdapter.Contacto>) {
        val vista = com.example.erikpy.databinding.DialogContactosBinding.inflate(layoutInflater)
        val adapter = ContactosAdapter(contactos) { c ->
            mostrar("Llamando a ${c.nombre}...")
            llamarContacto(c.numero)
            dialogoContactos?.dismiss()
        }
        vista.recyclerContactos.layoutManager =
            androidx.recyclerview.widget.LinearLayoutManager(requireContext())
        vista.recyclerContactos.adapter = adapter

        vista.inputBuscar.addTextChangedListener { texto ->
            val quedan = adapter.filtrar(texto?.toString() ?: "")
            vista.textVacio.visibility = if (quedan == 0) View.VISIBLE else View.GONE
            vista.recyclerContactos.visibility = if (quedan == 0) View.GONE else View.VISIBLE
        }

        dialogoContactos = MaterialAlertDialogBuilder(requireContext())
            .setTitle("Contactos (${contactos.size})")
            .setView(vista.root)
            .setPositiveButton("Cerrar", null)
            .setOnDismissListener { dialogoContactos = null }
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

    // --- Grabar mi voz (para clonarla en el VPS) ---

    private var grabador: GrabadorVoz? = null
    private var grabandoVoz = false
    private var archivoVoz: File? = null
    private var segundosGrab = 0
    private val handler = android.os.Handler(android.os.Looper.getMainLooper())
    private val cronometro = object : Runnable {
        override fun run() {
            if (!grabandoVoz) return
            segundosGrab++
            binding.buttonGrabarVoz.text =
                String.format(Locale.ROOT, "⏹️ Detener (%02d:%02d)", segundosGrab / 60, segundosGrab % 60)
            handler.postDelayed(this, 1000)
        }
    }

    private fun iniciarGrabacionVoz() {
        // Libera el micrófono de la escucha permanente si estuviera activa.
        requireContext().stopService(Intent(requireContext(), WakeWordService::class.java))
        binding.switchWake.isChecked = false

        val dir = requireContext().getExternalFilesDir(null)
        val archivo = File(dir, "mi_voz_${System.currentTimeMillis()}.wav")
        val g = GrabadorVoz(archivo)
        try {
            g.iniciar()
        } catch (e: Exception) {
            respond("No pude iniciar la grabación, Ariel: ${e.message}")
            return
        }
        grabador = g
        archivoVoz = archivo
        grabandoVoz = true
        segundosGrab = 0
        mostrar("Grabando tu voz, Ariel... habla natural y pulsa de nuevo para detener.")
        binding.buttonGrabarVoz.text = "⏹️ Detener (00:00)"
        handler.postDelayed(cronometro, 1000)
    }

    private fun detenerGrabacionVoz() {
        grabandoVoz = false
        handler.removeCallbacks(cronometro)
        grabador?.detener()
        grabador = null
        binding.buttonGrabarVoz.text = "Grabar mi voz (para clonarla)"

        val f = archivoVoz
        if (f != null && f.exists() && f.length() > 2048) {
            val kb = f.length() / 1024
            respond("Voz guardada, Ariel: ${f.name}, ${kb} KB, ${segundosGrab} segundos.")
            android.util.Log.i("ErikVoz", "Grabación guardada en: ${f.absolutePath}")
        } else {
            respond("La grabación salió vacía, Ariel. Revisa el permiso de micrófono e intenta de nuevo.")
        }
    }

    private var reproductor: android.media.MediaPlayer? = null

    /** Devuelve las grabaciones de voz guardadas, de más reciente a más antigua. */
    private fun listarGrabaciones(): List<File> {
        val dir = requireContext().getExternalFilesDir(null) ?: return emptyList()
        return (dir.listFiles { f -> f.name.startsWith("mi_voz_") && f.name.endsWith(".wav") }
            ?: emptyArray()).sortedByDescending { it.lastModified() }
    }

    /** Lista las grabaciones; al tocar una, ofrece reproducir o borrar. */
    private fun mostrarGrabaciones() {
        val grabaciones = listarGrabaciones()
        if (grabaciones.isEmpty()) {
            respond("Aún no tienes grabaciones de voz, Ariel. Usa \"Grabar mi voz\" primero.")
            return
        }
        val items = grabaciones.map { "${it.name}  (${it.length() / 1024} KB)" }.toTypedArray()
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("Mis grabaciones (${grabaciones.size})")
            .setItems(items) { _, which -> accionesGrabacion(grabaciones[which]) }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    /** Menú para una grabación: reproducir o borrar. */
    private fun accionesGrabacion(archivo: File) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle(archivo.name)
            .setItems(arrayOf("▶  Reproducir", "🗑  Borrar")) { _, opcion ->
                if (opcion == 0) reproducirGrabacion(archivo) else confirmarBorrado(archivo)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun reproducirGrabacion(archivo: File) {
        try {
            reproductor?.release()
            reproductor = android.media.MediaPlayer().apply {
                setDataSource(archivo.absolutePath)
                setOnCompletionListener { it.release(); if (reproductor === it) reproductor = null }
                prepare()
                start()
            }
            mostrar("Reproduciendo ${archivo.name}...")
        } catch (e: Exception) {
            respond("No pude reproducir la grabación, Ariel.")
        }
    }

    private fun confirmarBorrado(archivo: File) {
        MaterialAlertDialogBuilder(requireContext())
            .setTitle("¿Borrar grabación?")
            .setMessage(archivo.name)
            .setPositiveButton("Borrar") { _, _ ->
                if (archivo.delete()) {
                    respond("Grabación borrada, Ariel.")
                    mostrarGrabaciones()
                } else {
                    respond("No pude borrar la grabación, Ariel.")
                }
            }
            .setNegativeButton("Cancelar", null)
            .show()
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
        if (grabandoVoz) {
            grabandoVoz = false
            handler.removeCallbacks(cronometro)
            grabador?.detener(); grabador = null
        }
        reproductor?.release(); reproductor = null
        tts?.stop(); tts?.shutdown(); tts = null
        _binding = null
    }
}
