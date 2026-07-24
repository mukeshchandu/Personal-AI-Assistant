package com.naomi.assistant

import android.content.Context
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import kotlin.math.sqrt

/**
 * Stores the user's voice as a single 1024-dim neural embedding (averaged over 5+ samples)
 * and verifies incoming audio by cosine similarity.
 *
 * Replaces the old MFCC+DTW approach: the neural embedding is far more discriminative for
 * speaker identity — two different people saying "Naomi" land much farther apart.
 *
 * The embedding is computed by [SpeakerVerifier] (YAMNet) and persisted to disk.
 */
class VoiceEnrollment(context: Context) {

    private val verifier by lazy { SpeakerVerifier(context) }
    private val file = File(context.filesDir, EMBEDDING_FILE)
    private var storedEmbedding: FloatArray? = loadEmbedding()

    // Re-check disk each time so WakeService (created before enrollment) picks it up.
    val isEnrolled: Boolean
        get() {
            if (storedEmbedding == null) storedEmbedding = loadEmbedding()
            return storedEmbedding != null
        }

    val isModelAvailable: Boolean get() = verifier.isAvailable

    /**
     * Records the user's voice identity from [samples] (PCM-16, 16 kHz mono).
     * Embeds each sample and averages them into one unit-length enrollment vector.
     */
    fun enroll(samples: List<ShortArray>) {
        val embeds = samples.map { verifier.embed(it) }.filter { it.any { v -> v != 0f } }
        if (embeds.isEmpty()) { android.util.Log.e("Naomi", "Enrollment failed: no valid embeddings"); return }
        val avg = FloatArray(SpeakerVerifier.DIM)
        embeds.forEach { e -> for (i in avg.indices) avg[i] += e[i] }
        val n = embeds.size.toFloat()
        for (i in avg.indices) avg[i] /= n
        val norm = sqrt(avg.fold(0f) { acc, x -> acc + x * x })
        val enrolled = if (norm < 1e-8f) avg else FloatArray(avg.size) { avg[it] / norm }
        storedEmbedding = enrolled
        saveEmbedding(enrolled)
        android.util.Log.d("Naomi", "Enrolled voice from ${samples.size} samples (neural embedding)")
    }

    /**
     * Cosine similarity of [audio] vs the enrolled embedding: 0..1, higher = more similar.
     * Both vectors are L2-normalised, so this is just their dot product.
     * Returns 0 if not enrolled.
     */
    fun similarity(audio: ShortArray): Float {
        if (storedEmbedding == null) storedEmbedding = loadEmbedding()
        val stored = storedEmbedding ?: return 0f
        val emb = verifier.embed(audio)
        return emb.zip(stored.toList()).sumOf { (a, b) -> (a * b).toDouble() }.toFloat()
    }

    fun clear() { storedEmbedding = null; file.delete() }

    private fun saveEmbedding(emb: FloatArray) {
        try {
            DataOutputStream(file.outputStream().buffered()).use { out ->
                out.writeInt(emb.size)
                emb.forEach { out.writeFloat(it) }
            }
        } catch (e: Exception) {
            android.util.Log.e("Naomi", "embedding save failed: ${e.message}")
        }
    }

    private fun loadEmbedding(): FloatArray? {
        if (!file.exists()) return null
        return try {
            DataInputStream(file.inputStream().buffered()).use { inp ->
                val size = inp.readInt()
                val emb  = FloatArray(size) { inp.readFloat() }
                if (size != SpeakerVerifier.DIM) {
                    android.util.Log.w("Naomi",
                        "Stale embedding (dim=$size, expected=${SpeakerVerifier.DIM}) — clearing")
                    file.delete()
                    return null
                }
                emb
            }
        } catch (e: Exception) {
            android.util.Log.e("Naomi", "embedding load failed: ${e.message}")
            null
        }
    }

    companion object {
        private const val EMBEDDING_FILE = "speaker_embedding.bin"
    }
}
