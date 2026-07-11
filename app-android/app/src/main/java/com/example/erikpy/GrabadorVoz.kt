package com.example.erikpy

import android.annotation.SuppressLint
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import java.io.File
import java.io.RandomAccessFile

/** Graba audio del micrófono a un archivo WAV (PCM 16-bit, mono).
 *  Formato ideal para clonar la voz con XTTS. */
class GrabadorVoz(private val archivo: File) {

    private val sampleRate = 44100
    private val canal = AudioFormat.CHANNEL_IN_MONO
    private val formato = AudioFormat.ENCODING_PCM_16BIT

    private var record: AudioRecord? = null
    @Volatile private var grabando = false
    private var hilo: Thread? = null

    @SuppressLint("MissingPermission")
    fun iniciar() {
        val minBuf = AudioRecord.getMinBufferSize(sampleRate, canal, formato)
        val bufSize = if (minBuf > 0) maxOf(minBuf, sampleRate) else sampleRate
        record = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate, canal, formato, bufSize)
        record?.startRecording()
        grabando = true
        hilo = Thread { escribirWav(bufSize) }.also { it.start() }
    }

    fun detener() {
        grabando = false
        try { hilo?.join(1500) } catch (_: InterruptedException) {}
        try { record?.stop() } catch (_: Exception) {}
        record?.release()
        record = null
    }

    private fun escribirWav(bufSize: Int) {
        val raf = RandomAccessFile(archivo, "rw")
        raf.setLength(0)
        raf.write(ByteArray(44))  // deja hueco para la cabecera, se rellena al final
        val buffer = ByteArray(bufSize)
        var totalPcm = 0
        while (grabando) {
            val leido = record?.read(buffer, 0, buffer.size) ?: 0
            if (leido > 0) { raf.write(buffer, 0, leido); totalPcm += leido }
        }
        raf.seek(0)
        raf.write(cabeceraWav(totalPcm))
        raf.close()
    }

    /** Cabecera WAV de 44 bytes para PCM 16-bit mono. */
    private fun cabeceraWav(pcmBytes: Int): ByteArray {
        val byteRate = sampleRate * 2   // mono * 16 bits / 8
        val h = ByteArray(44)
        "RIFF".toByteArray().copyInto(h, 0)
        intLE(h, 4, pcmBytes + 36)
        "WAVE".toByteArray().copyInto(h, 8)
        "fmt ".toByteArray().copyInto(h, 12)
        intLE(h, 16, 16)        // tamaño del subchunk fmt
        shortLE(h, 20, 1)       // PCM
        shortLE(h, 22, 1)       // 1 canal (mono)
        intLE(h, 24, sampleRate)
        intLE(h, 28, byteRate)
        shortLE(h, 32, 2)       // block align
        shortLE(h, 34, 16)      // bits por muestra
        "data".toByteArray().copyInto(h, 36)
        intLE(h, 40, pcmBytes)
        return h
    }

    private fun intLE(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xff).toByte()
        b[off + 1] = ((v shr 8) and 0xff).toByte()
        b[off + 2] = ((v shr 16) and 0xff).toByte()
        b[off + 3] = ((v shr 24) and 0xff).toByte()
    }

    private fun shortLE(b: ByteArray, off: Int, v: Int) {
        b[off] = (v and 0xff).toByte()
        b[off + 1] = ((v shr 8) and 0xff).toByte()
    }
}
