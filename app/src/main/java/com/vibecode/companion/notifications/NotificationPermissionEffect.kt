package com.vibecode.companion.notifications

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.platform.LocalContext
import androidx.core.content.ContextCompat

// Process-wide guard so re-entering the composition doesn't nag the user again.
private var permissionRequested = false

/**
 * Requests POST_NOTIFICATIONS once on API 33+ when not yet granted.
 * Renders no UI — drop it anywhere inside the composition (e.g. the agent
 * list screen) so background polling can notify on terminal runs.
 */
@Composable
fun NotificationPermissionEffect() {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return

    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission(),
    ) { /* Result intentionally ignored — notifications silently no-op when denied. */ }

    LaunchedEffect(Unit) {
        if (permissionRequested) return@LaunchedEffect
        val granted = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.POST_NOTIFICATIONS,
        ) == PackageManager.PERMISSION_GRANTED
        if (!granted) {
            permissionRequested = true
            launcher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }
}
