package com.vibecode.companion.ui

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.produceState
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.vibecode.companion.CompanionApp
import com.vibecode.companion.notifications.NotificationPermissionEffect
import com.vibecode.companion.ui.agents.AgentListScreen
import com.vibecode.companion.ui.detail.AgentDetailScreen
import com.vibecode.companion.ui.launch.LaunchScreen
import com.vibecode.companion.ui.onboarding.OnboardingScreen

object Routes {
    const val ONBOARDING = "onboarding"
    const val AGENTS = "agents"
    const val LAUNCH = "launch"
    const val AGENT_DETAIL = "agent/{agentId}"
    fun agentDetail(agentId: String) = "agent/$agentId"
}

/**
 * @param initialAgentId deep-link target from a status notification
 *   (MainActivity reads the "agentId" intent extra).
 */
@Composable
fun AppNavHost(initialAgentId: String? = null) {
    val context = LocalContext.current
    val container = (context.applicationContext as CompanionApp).container
    val navController = rememberNavController()

    // Gate on the (async) DataStore read so we pick the right start destination once.
    val hasKey by produceState<Boolean?>(initialValue = null) {
        value = container.apiKeyStore.get() != null
    }

    when (hasKey) {
        null -> Box(modifier = Modifier.fillMaxSize())
        else -> {
            NavHost(
                navController = navController,
                startDestination = if (hasKey == true) Routes.AGENTS else Routes.ONBOARDING,
            ) {
                composable(Routes.ONBOARDING) {
                    OnboardingScreen(
                        onConnected = {
                            navController.navigate(Routes.AGENTS) {
                                popUpTo(0) { inclusive = true }
                            }
                        },
                    )
                }
                composable(Routes.AGENTS) {
                    NotificationPermissionEffect()
                    AgentListScreen(
                        onAgentClick = { id -> navController.navigate(Routes.agentDetail(id)) },
                        onLaunchClick = { navController.navigate(Routes.LAUNCH) },
                        onSignOut = {
                            navController.navigate(Routes.ONBOARDING) {
                                popUpTo(0) { inclusive = true }
                            }
                        },
                    )
                }
                composable(Routes.LAUNCH) {
                    LaunchScreen(
                        onLaunched = { id ->
                            navController.navigate(Routes.agentDetail(id)) {
                                popUpTo(Routes.AGENTS)
                            }
                        },
                        onBack = { navController.popBackStack() },
                    )
                }
                composable(
                    route = Routes.AGENT_DETAIL,
                    arguments = listOf(navArgument("agentId") { type = NavType.StringType }),
                ) { backStackEntry ->
                    val agentId = backStackEntry.arguments?.getString("agentId").orEmpty()
                    AgentDetailScreen(agentId = agentId, onBack = { navController.popBackStack() })
                }
            }

            LaunchedEffect(initialAgentId, hasKey) {
                if (hasKey == true && !initialAgentId.isNullOrBlank()) {
                    navController.navigate(Routes.agentDetail(initialAgentId))
                }
            }
        }
    }
}
