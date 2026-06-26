package com.vibecode.companion.ui.onboarding

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.OpenInNew
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalTextStyle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.vibecode.companion.ui.common.companionViewModel
import kotlinx.coroutines.delay

/**
 * Paste-a-key onboarding. Validates the key against GET /v1/me, persists it
 * encrypted via [com.vibecode.companion.data.storage.ApiKeyStore], then calls
 * [onConnected] after a brief greeting.
 */
@Composable
fun OnboardingScreen(onConnected: () -> Unit) {
    val vm = companionViewModel { container ->
        OnboardingViewModel(container.apiClient, container.apiKeyStore)
    }
    val state by vm.uiState.collectAsStateWithLifecycle()
    val context = LocalContext.current
    var keyVisible by remember { mutableStateOf(false) }

    LaunchedEffect(state.connectedInfo) {
        if (state.connectedInfo != null) {
            delay(1200)
            onConnected()
        }
    }

    Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                // Edge-to-edge: no Scaffold here, so keep content out of the
                // status bar / cutout / IME ourselves (safeDrawing covers all
                // three — before the scroll so the viewport shrinks with them).
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 24.dp, vertical = 32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            // Hero
            Text(
                text = "Agent Companion",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(8.dp))
            Text(
                text = "Steer your Cursor cloud agents from anywhere",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.primary,
                textAlign = TextAlign.Center,
            )
            Spacer(Modifier.height(16.dp))
            Text(
                text = "Create an API key at cursor.com/dashboard → API Keys, then paste it below. " +
                    "Cloud Agents require a paid Cursor plan with usage-based billing enabled.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )

            Spacer(Modifier.height(28.dp))

            OutlinedTextField(
                value = state.key,
                onValueChange = vm::onKeyChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("API key") },
                placeholder = { Text("crsr_...", fontFamily = FontFamily.Monospace) },
                singleLine = true,
                enabled = !state.isValidating && state.connectedInfo == null,
                isError = state.error != null,
                textStyle = LocalTextStyle.current.copy(fontFamily = FontFamily.Monospace),
                visualTransformation = if (keyVisible) {
                    VisualTransformation.None
                } else {
                    PasswordVisualTransformation()
                },
                trailingIcon = {
                    TextButton(onClick = { keyVisible = !keyVisible }) {
                        Text(
                            text = if (keyVisible) "Hide" else "Show",
                            style = MaterialTheme.typography.labelMedium,
                        )
                    }
                },
                keyboardOptions = KeyboardOptions(
                    keyboardType = KeyboardType.Password,
                    imeAction = ImeAction.Done,
                ),
                keyboardActions = KeyboardActions(onDone = { vm.connect() }),
            )

            if (state.error != null) {
                Spacer(Modifier.height(8.dp))
                Text(
                    text = state.error.orEmpty(),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.fillMaxWidth(),
                )
            }

            Spacer(Modifier.height(20.dp))

            Button(
                onClick = vm::connect,
                enabled = state.canConnect,
                modifier = Modifier.fillMaxWidth(),
            ) {
                if (state.isValidating) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(18.dp),
                        strokeWidth = 2.dp,
                        color = MaterialTheme.colorScheme.onPrimary,
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Validating…")
                } else {
                    Text("Connect")
                }
            }

            val info = state.connectedInfo
            if (info != null) {
                Spacer(Modifier.height(16.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Check,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.secondary,
                        modifier = Modifier.size(18.dp),
                    )
                    Spacer(Modifier.width(8.dp))
                    val who = info.userFirstName?.takeIf { it.isNotBlank() } ?: info.userEmail
                    Text(
                        text = if (who != null) "Connected — welcome, $who" else "Connected",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.secondary,
                    )
                }
            }

            Spacer(Modifier.height(12.dp))

            TextButton(
                onClick = {
                    try {
                        context.startActivity(
                            Intent(Intent.ACTION_VIEW, Uri.parse("https://cursor.com/dashboard")),
                        )
                    } catch (_: ActivityNotFoundException) {
                        // No browser installed — nothing sensible to do in a sample app.
                    }
                },
            ) {
                Icon(
                    imageVector = Icons.AutoMirrored.Filled.OpenInNew,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp),
                )
                Spacer(Modifier.width(6.dp))
                Text("Open Cursor Dashboard")
            }
        }
    }
}
