package com.vibecode.companion

import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Modifier
import com.vibecode.companion.ui.AppNavHost
import com.vibecode.companion.ui.theme.CompanionTheme
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dark-first UI: keep system bar icons light over the ink canvas
        // regardless of the device's light/dark setting.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        // Set by status notifications (see AgentNotifications) to deep-link into a run.
        // Only honored on fresh launches — recreations (rotation/theme change) restore
        // their own nav state and must not re-trigger the deep link.
        val initialAgentId = if (savedInstanceState == null) intent?.getStringExtra("agentId") else null
        intent?.removeExtra("agentId")
        // The Compose root: make Metro's ViewModel factory available so screens can use
        // metroViewModel() / assistedMetroViewModel().
        val viewModelFactory = (application as CompanionApp).graph.metroViewModelFactory
        setContent {
            CompanionTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    CompositionLocalProvider(LocalMetroViewModelFactory provides viewModelFactory) {
                        AppNavHost(initialAgentId = initialAgentId)
                    }
                }
            }
        }
    }
}
