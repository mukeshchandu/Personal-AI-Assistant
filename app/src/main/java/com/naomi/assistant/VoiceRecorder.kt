package com.naomi.assistant

import android.content.ContentValues
import android.content.Context
import android.media.MediaRecorder
import android.os.Build
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Singleton in-app voice recorder. Saves M4A files to Audio/Recordings by Naomi/
 * in public storage via MediaStore (visible in Files app, no extra permissions needed).
 *
 * Caller is responsible for pausing/resuming WakeService around a session (mic conflict).
 */
object VoiceRecorder {

    @Volatile var isRecording = false
        private set

    private var recorder: MediaRecorder? = null
    private var currentDisplayName: String? = null

    fun start(context: Context): String {
        if (isRecording) return "Already recording. Tap the orb to stop."

        val stamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val displayName = "naomi_recording_$stamp.m4a"

        val values = ContentValues().apply {
            put(MediaStore.Audio.Media.DISPLAY_NAME, displayName)
            put(MediaStore.Audio.Media.MIME_TYPE, "audio/mp4")
            put(MediaStore.Audio.Media.RELATIVE_PATH, "Recordings/Naomi")
        }

        val uri = context.contentResolver.insert(
            MediaStore.Audio.Media.EXTERNAL_CONTENT_URI, values
        ) ?: return "Sorry, couldn't create the recording file."

        val pfd = try {
            context.contentResolver.openFileDescriptor(uri, "w")
        } catch (e: Exception) {
            return "Sorry, couldn't open the recording file: ${e.message}"
        } ?: return "Sorry, couldn't open the recording file."

        val rec = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(context)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        return try {
            rec.apply {
                setAudioSource(MediaRecorder.AudioSource.MIC)
                setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(128_000)
                setAudioSamplingRate(44100)
                setOutputFile(pfd.fileDescriptor)
                prepare()
                start()
            }
            recorder = rec
            currentDisplayName = displayName
            isRecording = true
            android.util.Log.i("Naomi", "Recording started: $displayName")
            "Recording started. Tap the orb when you're done."
        } catch (e: Exception) {
            android.util.Log.e("Naomi", "Recording start failed: ${e.message}")
            try { rec.release() } catch (_: Exception) {}
            try { pfd.close() } catch (_: Exception) {}
            "Sorry, I couldn't start recording: ${e.message}"
        }
    }

    fun stop(): String {
        val rec = recorder ?: return "No recording in progress."
        return try {
            rec.stop()
            rec.release()
            android.util.Log.i("Naomi", "Recording saved: $currentDisplayName")
            "Recording saved to Recordings, Naomi folder."
        } catch (e: Exception) {
            android.util.Log.e("Naomi", "Recording stop failed: ${e.message}")
            "Recording stopped."
        } finally {
            recorder = null
            currentDisplayName = null
            isRecording = false
        }
    }
}
