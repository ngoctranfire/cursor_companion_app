package com.vibecode.companion.ui.launch

import android.app.Activity
import android.content.ActivityNotFoundException
import android.content.Intent
import android.speech.RecognizerIntent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier

/**
 * Mic button that launches the system speech recognizer and reports the top
 * transcription via [onResult]. If no recognizer activity exists on the device
 * the tap is silently ignored (typing remains available).
 */
@Composable
fun VoiceInputButton(onResult: (String) -> Unit, modifier: Modifier = Modifier) {
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.StartActivityForResult(),
    ) { result ->
        if (result.resultCode == Activity.RESULT_OK) {
            result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
                ?.takeIf { it.isNotBlank() }
                ?.let(onResult)
        }
    }

    IconButton(
        onClick = {
            val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
                putExtra(
                    RecognizerIntent.EXTRA_LANGUAGE_MODEL,
                    RecognizerIntent.LANGUAGE_MODEL_FREE_FORM,
                )
                putExtra(RecognizerIntent.EXTRA_PROMPT, "Describe the task")
            }
            try {
                launcher.launch(intent)
            } catch (_: ActivityNotFoundException) {
                // No speech recognizer installed — nothing to do for MVP.
            }
        },
        modifier = modifier,
        colors = IconButtonDefaults.iconButtonColors(
            contentColor = MaterialTheme.colorScheme.primary,
        ),
    ) {
        Icon(Icons.Default.Mic, contentDescription = "Dictate prompt")
    }
}
