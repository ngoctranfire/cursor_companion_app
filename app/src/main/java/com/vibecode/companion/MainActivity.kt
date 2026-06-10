package com.vibecode.companion

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import com.vibecode.companion.ui.AppNavHost
import com.vibecode.companion.ui.theme.CompanionTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // Set by status notifications (see AgentNotifications) to deep-link into a run.
        // Only honored on fresh launches — recreations (rotation/theme change) restore
        // their own nav state and must not re-trigger the deep link.
        val initialAgentId = if (savedInstanceState == null) intent?.getStringExtra("agentId") else null
        intent?.removeExtra("agentId")
        setContent {
            CompanionTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background,
                ) {
                    AppNavHost(initialAgentId = initialAgentId)
                }
            }
        }
    }
}
