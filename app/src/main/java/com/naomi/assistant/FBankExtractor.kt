package com.naomi.assistant

import kotlin.math.*

/**
 * Kaldi-compatible 80-dim log-mel filterbank feature extractor.
 * Matches the WeSpeaker preprocessor config:
 *   sample_rate=16000, frame_length=25ms, frame_shift=10ms, num_mel_bins=80,
 *   window_type=hamming, f_min=20 Hz, f_max=7600 Hz, no energy coefficient.
 *
 * Designed to run on the main Android thread or a coroutine dispatcher — pure Kotlin, no deps.
 */
object FBankExtractor {

    private const val SAMPLE_RATE = 16000
    private const val WIN_LENGTH  = 400   // 25 ms at 16 kHz
    private const val HOP_LENGTH  = 160   // 10 ms at 16 kHz
    private const val N_FFT       = 512
    const  val N_MELS             = 80
    private const val F_MIN       = 20.0
    private const val F_MAX       = 7600.0

    private val HAMMING: FloatArray = FloatArray(WIN_LENGTH) { n ->
        (0.54 - 0.46 * cos(2 * PI * n / (WIN_LENGTH - 1))).toFloat()
    }

    // Mel filterbank weights [N_MELS × (N_FFT/2+1)] — computed once at first access.
    private val MEL_FILTERS: Array<FloatArray> by lazy { buildMelFilters() }

    private fun hzToMel(hz: Double) = 2595.0 * log10(1.0 + hz / 700.0)
    private fun melToHz(mel: Double) = 700.0 * (10.0.pow(mel / 2595.0) - 1.0)

    private fun buildMelFilters(): Array<FloatArray> {
        val numBins = N_FFT / 2 + 1
        val melMin  = hzToMel(F_MIN)
        val melMax  = hzToMel(F_MAX)
        // N_MELS + 2 linearly spaced mel points, converted back to Hz, then to FFT bins
        val hz = DoubleArray(N_MELS + 2) { i ->
            melToHz(melMin + i * (melMax - melMin) / (N_MELS + 1))
        }
        val bin = IntArray(N_MELS + 2) { i ->
            ((hz[i] / SAMPLE_RATE) * N_FFT).toInt().coerceIn(0, numBins - 1)
        }
        return Array(N_MELS) { m ->
            FloatArray(numBins) { k ->
                val lo = bin[m]; val mid = bin[m + 1]; val hi = bin[m + 2]
                when {
                    k in (lo + 1) until mid  -> (k - lo).toFloat() / (mid - lo).coerceAtLeast(1)
                    k == mid                  -> 1f
                    k in (mid + 1) until hi  -> (hi - k).toFloat() / (hi - mid).coerceAtLeast(1)
                    else                      -> 0f
                }
            }
        }
    }

    /** In-place radix-2 Cooley-Tukey FFT; returns power spectrum |X[k]|² for k in 0..N/2. */
    private fun powerSpectrum(frame: FloatArray): FloatArray {
        val n  = N_FFT
        val re = DoubleArray(n) { if (it < frame.size) frame[it].toDouble() else 0.0 }
        val im = DoubleArray(n)

        // Bit-reversal
        var j = 0
        for (i in 1 until n) {
            var bit = n shr 1
            while (j and bit != 0) { j = j xor bit; bit = bit shr 1 }
            j = j xor bit
            if (i < j) {
                re[i] = re[j].also { re[j] = re[i] }
                im[i] = im[j].also { im[j] = im[i] }
            }
        }

        // Butterfly stages
        var len = 2
        while (len <= n) {
            val angle = 2 * PI / len
            val wrStep = cos(angle); val wiStep = -sin(angle)
            var pos = 0
            while (pos < n) {
                var wr = 1.0; var wi = 0.0
                for (i in 0 until len / 2) {
                    val ur = re[pos + i];     val ui = im[pos + i]
                    val vr = re[pos+i+len/2] * wr - im[pos+i+len/2] * wi
                    val vi = re[pos+i+len/2] * wi + im[pos+i+len/2] * wr
                    re[pos + i]       = ur + vr; im[pos + i]       = ui + vi
                    re[pos+i+len/2]   = ur - vr; im[pos+i+len/2]   = ui - vi
                    val nr = wr * wrStep - wi * wiStep; wi = wr * wiStep + wi * wrStep; wr = nr
                }
                pos += len
            }
            len = len shl 1
        }

        return FloatArray(n / 2 + 1) { k -> (re[k] * re[k] + im[k] * im[k]).toFloat() }
    }

    /**
     * Computes log-mel features from raw PCM-16 audio (16 kHz mono).
     * Returns a flat [frames × N_MELS] FloatArray and the frame count,
     * ready to wrap in an ONNX tensor of shape [1, frames, N_MELS].
     */
    fun compute(audio: ShortArray): Pair<FloatArray, Int> {
        val filters   = MEL_FILTERS
        val numFrames = ((audio.size - WIN_LENGTH) / HOP_LENGTH + 1).coerceAtLeast(0)
        val out       = FloatArray(numFrames * N_MELS)

        for (f in 0 until numFrames) {
            val start = f * HOP_LENGTH
            val frame = FloatArray(WIN_LENGTH) { i -> (audio[start + i] / 32768f) * HAMMING[i] }
            val power = powerSpectrum(frame)
            for (m in 0 until N_MELS) {
                var energy = 0f
                for (k in power.indices) energy += filters[m][k] * power[k]
                out[f * N_MELS + m] = ln(energy.coerceAtLeast(1e-6f))
            }
        }
        return out to numFrames
    }
}
