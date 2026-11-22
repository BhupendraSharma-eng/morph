package bhupendra.morph.device

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlin.math.sqrt

/** Shared bits for Morph's two voices (Gemini TTS in [Speech], Gemini Live in LiveSession): 16-bit mono PCM. */
object Pcm {
    /** A streaming track for 24 kHz mono speech (what Gemini sends back). */
    fun track24k(attributes: AudioAttributes): AudioTrack = AudioTrack.Builder()
        .setAudioAttributes(attributes)
        .setAudioFormat(AudioFormat.Builder().setSampleRate(24_000).setEncoding(AudioFormat.ENCODING_PCM_16BIT)
            .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
        .setTransferMode(AudioTrack.MODE_STREAM)
        .setBufferSizeInBytes(AudioTrack.getMinBufferSize(24_000, AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_16BIT) * 4)
        .build()

    /** Loudness 0..1 of the first [n] bytes (sampled every 16th sample — it only drives the orb). */
    fun level(pcm: ByteArray, n: Int = pcm.size): Float {
        var sum = 0.0; var count = 0; var i = 0
        while (i + 1 < n) { val s = (pcm[i].toInt() and 0xff) or (pcm[i + 1].toInt() shl 8); sum += s.toDouble() * s; count++; i += 32 }
        return if (count == 0) 0f else (sqrt(sum / count) / 5500.0).toFloat().coerceIn(0f, 1f)
    }
}
