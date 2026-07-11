package com.example.erikpy

import android.content.res.ColorStateList
import android.graphics.Color
import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.RecyclerView
import com.example.erikpy.databinding.ItemContactoBinding
import java.text.Normalizer
import java.util.Locale

/** Lista profesional de contactos: avatar con inicial de color, nombre, número
 *  e icono de llamar. Soporta filtrado por nombre o número. */
class ContactosAdapter(
    private val todos: List<Contacto>,
    private val onLlamar: (Contacto) -> Unit
) : RecyclerView.Adapter<ContactosAdapter.VH>() {

    data class Contacto(val nombre: String, val numero: String)

    private var visibles: List<Contacto> = todos

    // Paleta para los avatares (color fijo por contacto, según su nombre).
    private val paleta = intArrayOf(
        Color.parseColor("#EF5350"), Color.parseColor("#AB47BC"),
        Color.parseColor("#5C6BC0"), Color.parseColor("#29B6F6"),
        Color.parseColor("#26A69A"), Color.parseColor("#66BB6A"),
        Color.parseColor("#FFA726"), Color.parseColor("#8D6E63")
    )

    inner class VH(val b: ItemContactoBinding) : RecyclerView.ViewHolder(b.root)

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val b = ItemContactoBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return VH(b)
    }

    override fun getItemCount() = visibles.size

    override fun onBindViewHolder(holder: VH, position: Int) {
        val c = visibles[position]
        holder.b.textNombre.text = c.nombre
        holder.b.textNumero.text = c.numero
        val inicial = c.nombre.trim().firstOrNull { it.isLetter() }?.uppercaseChar() ?: '#'
        holder.b.textAvatar.text = inicial.toString()
        val color = paleta[Math.floorMod(c.nombre.hashCode(), paleta.size)]
        holder.b.textAvatar.backgroundTintList = ColorStateList.valueOf(color)
        holder.b.root.setOnClickListener { onLlamar(c) }
        holder.b.buttonLlamar.setOnClickListener { onLlamar(c) }
    }

    /** Filtra por nombre (sin acentos) o por dígitos del número. Devuelve cuántos quedan. */
    fun filtrar(consulta: String): Int {
        val q = normalizar(consulta)
        val qDigitos = soloDigitos(consulta)
        visibles = if (q.isEmpty()) todos else todos.filter {
            normalizar(it.nombre).contains(q) ||
                (qDigitos.isNotEmpty() && soloDigitos(it.numero).contains(qDigitos))
        }
        notifyDataSetChanged()
        return visibles.size
    }

    private fun soloDigitos(s: String) = s.filter { it.isDigit() }

    private fun normalizar(t: String): String {
        val nfd = Normalizer.normalize(t, Normalizer.Form.NFD).replace(Regex("\\p{Mn}+"), "")
        return nfd.lowercase(Locale.ROOT).trim()
    }
}
