package com.vibecode.companion

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.vibecode.companion.notifications.AgentNotifications
import com.vibecode.companion.ui.AppNavHost
import com.vibecode.companion.ui.theme.CompanionTheme
import dev.zacsweers.metrox.viewmodel.LocalMetroViewModelFactory

class MainActivity : ComponentActivity() {
    private var deepLinkAgentId by mutableStateOf<String?>(null)
    private var deepLinkEventId by mutableLongStateOf(0L)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Dark-first UI: keep system bar icons light over the ink canvas
        // regardless of the device's light/dark setting.
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(Color.TRANSPARENT),
        )
        // Set by status notifications (see AgentNotifications) to deep-link into an agent.
        // Only honored on fresh launches — recreations (rotation/theme change) restore
        // their own nav state and must not re-trigger the deep link.
        if (savedInstanceState == null) handleDeepLinkIntent(intent)
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
                        AppNavHost(
                            deepLinkAgentId = deepLinkAgentId,
                            deepLinkEventId = deepLinkEventId,
                        )
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleDeepLinkIntent(intent)
    }

    private fun handleDeepLinkIntent(intent: Intent?) {
        val agentId = intent?.getStringExtra(AgentNotifications.EXTRA_AGENT_ID)
        intent?.removeExtra(AgentNotifications.EXTRA_AGENT_ID)
        if (!agentId.isNullOrBlank()) {
            deepLinkAgentId = agentId
            deepLinkEventId = SystemClock.elapsedRealtimeNanos()
        }
    }
}
