package com.naomi.assistant

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch

/**
 * Naomi — first slice.
 *
 * Flow: tap the button -> listen (SpeechRecognizer) -> AssistantBrain (offline router or
 * Gemini) -> speak the reply (TextToSpeech). The transcript shows what's happening.
 *
 * Next increments: replace the button with a "Hey Naomi" wake word, and add Vosk for
 * fully-offline speech-to-text.
 */
class MainActivity : ComponentActivity() {

    private lateinit var voice: VoiceInput
    private lateinit var speaker: Speaker
    private lateinit var brain: AssistantBrain

    private var status by mutableStateOf("Tap and speak")
    private var transcript by mutableStateOf("")

    private val micPermission =
        registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
            status = if (granted) "Tap and speak" else "Microphone permission is required"
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        voice = VoiceInput(this)
        speaker = Speaker(this)
        // BuildConfig.GEMINI_API_KEY comes from local.properties (see README).
        brain = AssistantBrain(this, BuildConfig.GEMINI_API_KEY)

        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    Column(
                        modifier = Modifier.fillMaxSize().padding(24.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text("Naomi", style = MaterialTheme.typography.headlineMedium)
                        Text(
                            status,
                            modifier = Modifier.padding(top = 8.dp, bottom = 32.dp),
                            style = MaterialTheme.typography.bodyMedium
                        )
                        Button(
                            onClick = { onMicTapped() },
                            modifier = Modifier.size(160.dp)
                        ) { Text("🎤  Talk") }
                        if (transcript.isNotBlank()) {
                            Text(
                                transcript,
                                modifier = Modifier.padding(top = 32.dp),
                                style = MaterialTheme.typography.bodyLarge
                            )
                        }
                    }
                }
            }
        }
    }

    private fun onMicTapped() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermission.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        status = "Listening…"
        voice.listen(
            onResult = { spoken ->
                transcript = "You: $spoken"
                status = "Thinking…"
                lifecycleScope.launch {
                    val reply = brain.handle(spoken)
                    transcript = "You: $spoken\n\nNaomi: $reply"
                    status = "Tap and speak"
                    speaker.speak(reply)
                }
            },
            onError = { message ->
                status = message
            }
        )
    }

    override fun onDestroy() {
        voice.destroy()
        speaker.shutdown()
        super.onDestroy()
    }
}
