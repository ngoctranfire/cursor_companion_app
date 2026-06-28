package com.vibecode.companion.ui.agents

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.vibecode.companion.data.api.AgentEnv
import com.vibecode.companion.data.api.AgentStatus
import com.vibecode.companion.data.api.CloudAgent
import com.vibecode.companion.ui.theme.CompanionTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot regression tests for [AgentListContent] across loaded, empty, and
 * error states. Each renders the stateless content from a fixture
 * [AgentListUiState] so the whole list screen is captured deterministically.
 *
 * Renders off-device via Roborazzi + Robolectric (native graphics). Record the
 * goldens with `./gradlew :app:recordRoborazziDebug`; CI/verify compares against
 * them with `./gradlew :app:verifyRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class AgentListScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** Renders [state] through [AgentListContent] and captures the full screen to [name]. */
    private fun capture(name: String, state: AgentListUiState) {
        composeRule.setContent {
            CompanionTheme {
                AgentListContent(
                    state = state,
                    snackbarHostState = remember { SnackbarHostState() },
                    onAgentClick = {},
                    onLaunchClick = {},
                    onRefresh = {},
                    onLoadMore = {},
                    onArchive = {},
                    onRetry = {},
                    onSignOut = {},
                )
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    /** The populated list: several agent cards (incl. an archived one) plus "Load more". */
    @Test
    fun agentList_loaded() {
        capture(
            "AgentList_loaded",
            AgentListUiState(items = SAMPLE_AGENTS, nextCursor = "cursor_page_2"),
        )
    }

    /** The empty state: the brand mark plus the "Launch your first agent" call to action. */
    @Test
    fun agentList_empty() {
        capture("AgentList_empty", AgentListUiState(items = emptyList()))
    }

    /** The error state: the "Couldn't load agents" message with a Retry button. */
    @Test
    fun agentList_error() {
        capture(
            "AgentList_error",
            AgentListUiState(items = emptyList(), error = "Check your connection"),
        )
    }

    private companion object {
        // updatedAt holds literal strings on purpose: relativeTime() can't parse them,
        // so it returns them verbatim and the golden never drifts with wall-clock time.
        val SAMPLE_AGENTS = listOf(
            CloudAgent(
                id = "a1",
                name = "Fix flaky login test",
                status = AgentStatus.ACTIVE,
                env = AgentEnv(type = "cloud"),
                createdAt = "2h ago",
                updatedAt = "2h ago",
            ),
            CloudAgent(
                id = "a2",
                name = "Add dark mode toggle to settings",
                status = AgentStatus.ACTIVE,
                env = AgentEnv(type = "cloud"),
                createdAt = "5h ago",
                updatedAt = "5h ago",
            ),
            CloudAgent(
                id = "a3",
                name = "Refactor payment service",
                status = AgentStatus.ARCHIVED,
                env = AgentEnv(type = "cloud"),
                createdAt = "1d ago",
                updatedAt = "1d ago",
            ),
            CloudAgent(
                id = "a4",
                name = null,
                status = AgentStatus.ACTIVE,
                env = AgentEnv(type = "cloud"),
                createdAt = "3d ago",
                updatedAt = "3d ago",
            ),
        )
    }
}
