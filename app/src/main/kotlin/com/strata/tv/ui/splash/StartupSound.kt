package com.strata.tv.ui.splash

import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlin.math.PI
import kotlin.math.exp
import kotlin.math.sin

/**
 * Strata audio signature.
 *
 * Aim: short, melodic, distinctive but gentle.  In the same family as
 * Disney+'s soft chime or Apple TV+'s pad rather than Netflix's
 * aggressive "ta-dum".  About 2 seconds, one note doing the heavy
 * lifting at any time so it doesn't feel cluttered, generous decay
 * so each note rings out.
 *
 * Motif: a soft pad in F major slides into a bell-like three-note
 * arpeggio (F5 -> A5 -> C6) on top.  Pad + bells share the chord so
 * the overall timbre stays consonant.
 *
 * Generated procedurally — no audio assets — and played fire-and-
 * forget via [AudioTrack.MODE_STATIC] so we don't have to manage
 * playback lifecycle from the composable.
 */
object StartupSound {

    private const val SAMPLE_RATE = 44100
    private const val DURATION_MS = 2200

    // Frequencies in F major so the chord resolves warmly.
    private const val F3 = 174.61      // sub root
    private const val F4 = 349.23      // pad root
    private const val A4 = 440.00      // pad third
    private const val C5 = 523.25      // pad fifth
    private const val F5 = 698.46      // bell 1
    private const val A5 = 880.00      // bell 2
    private const val C6 = 1046.50     // bell 3

    suspend fun play() = withContext(Dispatchers.Default) {
        val totalSamples = SAMPLE_RATE * DURATION_MS / 1000
        val buffer = ShortArray(totalSamples)

        // -- Pad: long, slow attack, even slower decay so it forms
        //    the harmonic bed the bells sit on top of.  Three voices
        //    voiced low so they feel like a warm cushion, not a drone.
        addSine(buffer, F3, startMs = 0, endMs = 2200, volume = 0.06, attackMs = 400, decayMs = 900)
        addSine(buffer, F4, startMs = 100, endMs = 2200, volume = 0.10, attackMs = 350, decayMs = 900)
        addSine(buffer, A4, startMs = 250, endMs = 2200, volume = 0.07, attackMs = 350, decayMs = 900)
        addSine(buffer, C5, startMs = 400, endMs = 2200, volume = 0.05, attackMs = 300, decayMs = 900)

        // -- Bell arpeggio: short attack + long decay, struck one
        //    after another.  Each bell uses sine + faint harmonic so
        //    it has a bit of character without sounding metallic.
        addBell(buffer, F5, startMs = 500,  durationMs = 1700, volume = 0.18)
        addBell(buffer, A5, startMs = 850,  durationMs = 1350, volume = 0.16)
        addBell(buffer, C6, startMs = 1200, durationMs = 1000, volume = 0.14)

        val minBufSize = AudioTrack.getMinBufferSize(
            SAMPLE_RATE,
            AudioFormat.CHANNEL_OUT_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
        )
        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC)
                    .build(),
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(SAMPLE_RATE)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .build(),
            )
            .setBufferSizeInBytes(maxOf(buffer.size * 2, minBufSize))
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        track.write(buffer, 0, buffer.size)
        track.play()
    }

    /**
     * Bell-like voice: fundamental sine + faint octave + faint twelfth
     * with a percussive attack and very long exponential decay.  Sounds
     * close to a small chime rather than a buzz or organ note.
     */
    private fun addBell(
        buffer: ShortArray,
        freq: Double,
        startMs: Int,
        durationMs: Int,
        volume: Double,
    ) {
        addEnvelope(
            buffer = buffer,
            freq = freq,
            startMs = startMs,
            endMs = startMs + durationMs,
            volume = volume,
            attackMs = 8,           // crisp strike
            decayPower = 4.0,       // strong exponential decay (bell-like)
        )
        addEnvelope(
            buffer = buffer,
            freq = freq * 2.0,      // octave
            startMs = startMs,
            endMs = startMs + (durationMs * 6 / 10),
            volume = volume * 0.25,
            attackMs = 6,
            decayPower = 5.0,
        )
        addEnvelope(
            buffer = buffer,
            freq = freq * 3.0,      // perfect twelfth, gives it shimmer
            startMs = startMs,
            endMs = startMs + (durationMs * 4 / 10),
            volume = volume * 0.10,
            attackMs = 4,
            decayPower = 6.0,
        )
    }

    /**
     * Pure sine voice with a smooth attack ramp and gentle decay.
     * Used for the harmonic pad bed underneath the bells.
     */
    private fun addSine(
        buffer: ShortArray,
        freq: Double,
        startMs: Int,
        endMs: Int,
        volume: Double,
        attackMs: Int,
        decayMs: Int,
    ) {
        val startSample = (SAMPLE_RATE * startMs / 1000).coerceIn(0, buffer.size)
        val endSample = (SAMPLE_RATE * endMs / 1000).coerceIn(0, buffer.size)
        val attackSamples = SAMPLE_RATE * attackMs / 1000
        val decaySamples = SAMPLE_RATE * decayMs / 1000

        for (i in startSample until endSample) {
            val t = (i - startSample).toDouble()
            val samplesFromEnd = (endSample - i).toDouble()
            val attack = if (t < attackSamples) {
                // Smoothstep for a more natural attack curve.
                val x = t / attackSamples
                x * x * (3 - 2 * x)
            } else 1.0
            val decay = if (samplesFromEnd < decaySamples) {
                samplesFromEnd / decaySamples
            } else 1.0
            val envelope = attack * decay * volume
            val sample = sin(2.0 * PI * freq * i / SAMPLE_RATE)
            val mixed = buffer[i] + (sample * envelope * Short.MAX_VALUE).toInt()
            buffer[i] = mixed.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }

    /**
     * Sine voice with a percussive attack and an exponential decay
     * controlled by [decayPower].  Higher powers = faster decay,
     * snappier "strike" feel.  Used for bell components.
     */
    private fun addEnvelope(
        buffer: ShortArray,
        freq: Double,
        startMs: Int,
        endMs: Int,
        volume: Double,
        attackMs: Int,
        decayPower: Double,
    ) {
        val startSample = (SAMPLE_RATE * startMs / 1000).coerceIn(0, buffer.size)
        val endSample = (SAMPLE_RATE * endMs / 1000).coerceIn(0, buffer.size)
        val attackSamples = SAMPLE_RATE * attackMs / 1000
        val span = (endSample - startSample).toDouble().coerceAtLeast(1.0)

        for (i in startSample until endSample) {
            val t = (i - startSample).toDouble()
            val attack = if (t < attackSamples) t / attackSamples else 1.0
            // Exponential decay from peak to zero over the whole span.
            val decay = exp(-decayPower * (t / span))
            val envelope = attack * decay * volume
            val sample = sin(2.0 * PI * freq * i / SAMPLE_RATE)
            val mixed = buffer[i] + (sample * envelope * Short.MAX_VALUE).toInt()
            buffer[i] = mixed.coerceIn(Short.MIN_VALUE.toInt(), Short.MAX_VALUE.toInt()).toShort()
        }
    }
}
