package com.naomi.assistant

import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import android.content.Context
import java.io.File
import java.nio.FloatBuffer
import kotlin.math.sqrt

/**
 * Speaker embedder backed by ECAPA-TDNN (~26 MB ONNX).
 *
 * Pipeline:
 *   raw PCM-16 (16 kHz mono) → pad/trim to FRAMES frames
 *     → FBankExtractor  → [1, FRAMES, 80] log-mel features
 *     → ONNX model      → [1, 192] L2-normalised speaker embedding
 *
 * Model file: getExternalFilesDir(null)/ecapa_speaker.onnx  (push via adb).
 */
class SpeakerVerifier(context: Context) {

    private val modelFile = File(context.getExternalFilesDir(null), MODEL_FILE)
    private val env = OrtEnvironment.getEnvironment()
    private var session: OrtSession? = null

    val isAvailable: Boolean get() = modelFile.exists()

    private fun ensureSession(): OrtSession? {
        session?.let { return it }
        if (!modelFile.exists()) return null
        return try {
            val opts = OrtSession.SessionOptions().apply {
                setIntraOpNumThreads(2)
                setInterOpNumThreads(1)
            }
            env.createSession(modelFile.absolutePath, opts).also { session = it }
        } catch (e: Exception) {
            android.util.Log.e("Naomi", "ECAPA session load failed: ${e.message}")
            null
        }
    }

    /**
     * Embeds [audio] (PCM-16, 16 kHz mono) into a unit-length [DIM]-dim d-vector.
     * Audio is trimmed or zero-padded to exactly [SAMPLES] samples before inference.
     * Returns a zero vector if the model file isn't present.
     */
    fun embed(audio: ShortArray): FloatArray {
        val sess = ensureSession() ?: return FloatArray(DIM)
        return try {
            // Pad or trim to the fixed window the model was exported with.
            val fixed = ShortArray(SAMPLES)
            audio.copyInto(fixed, endIndex = minOf(audio.size, SAMPLES))

            val (flatFeats, _) = FBankExtractor.compute(fixed)

            // Pad feature rows to exactly FRAMES if compute produced fewer (edge case).
            val needed = FRAMES * FBankExtractor.N_MELS
            val paddedFeats = if (flatFeats.size >= needed) flatFeats
                             else flatFeats.copyOf(needed)

            val shape      = longArrayOf(1, FRAMES.toLong(), FBankExtractor.N_MELS.toLong())
            val featTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(paddedFeats, 0, needed), shape)
            val lensTensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(floatArrayOf(1.0f)), longArrayOf(1))

            val output = sess.run(mapOf("feats" to featTensor, "wav_lens" to lensTensor))

            val raw = output[0].value
            val flat = when (raw) {
                is Array<*> -> (raw as Array<FloatArray>)[0]
                is FloatArray -> raw
                else -> FloatArray(DIM)
            }
            featTensor.close(); lensTensor.close(); output.close()
            l2Normalize(flat.copyOf(DIM))
        } catch (e: Exception) {
            android.util.Log.e("Naomi", "ECAPA embed failed: ${e.message}")
            FloatArray(DIM)
        }
    }

    fun close() { session?.close(); session = null }

    private fun l2Normalize(v: FloatArray): FloatArray {
        val norm = sqrt(v.fold(0f) { acc, x -> acc + x * x })
        return if (norm < 1e-8f) v else FloatArray(v.size) { v[it] / norm }
    }

    companion object {
        const val MODEL_FILE = "ecapa_speaker.onnx"
        private const val INPUT_NAME = "feats"
        const val DIM = 192

        // Fixed window: 1.5 s @ 16 kHz = 24000 samples → ~149 fbank frames
        const val SAMPLES = 24000
        const val FRAMES  = 149
    }
}
