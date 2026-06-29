package com.vibecode.companion.ui.detail

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.vibecode.companion.data.api.AgentMode
import com.vibecode.companion.data.api.AgentStatus
import com.vibecode.companion.data.api.CloudAgent
import com.vibecode.companion.data.api.RepoConfig
import com.vibecode.companion.data.api.Run
import com.vibecode.companion.data.api.RunGit
import com.vibecode.companion.data.api.RunGitBranch
import com.vibecode.companion.data.api.RunStatus
import com.vibecode.companion.ui.theme.CompanionTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot regression tests for [AgentDetailContent] across a populated run
 * timeline, the no-runs-yet body, and the full-screen load error. Each renders
 * the stateless content from a fixture [AgentDetailUiState] so the whole detail
 * screen is captured deterministically (terminal run statuses keep the
 * status dots static, so there are no live animations to drift the golden).
 *
 * Renders off-device via Roborazzi + Robolectric (native graphics). Record the
 * goldens with `./gradlew :app:recordRoborazziDebug`; CI/verify compares against
 * them with `./gradlew :app:verifyRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class AgentDetailScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** Renders [state] through [AgentDetailContent] and captures the full screen to [name]. */
    private fun capture(name: String, state: AgentDetailUiState) {
        composeRule.setContent {
            CompanionTheme {
                AgentDetailContent(
                    state = state,
                    snackbarHostState = remember { SnackbarHostState() },
                    onBack = {},
                    onShareAgentLink = {},
                    onRefresh = {},
                    onCancelRun = {},
                    onRetryLoad = {},
                    onReconnect = {},
                    onViewPastRun = {},
                    onViewLatest = {},
                    onFollowUpTextChange = {},
                    onSendFollowUp = {},
                    onBuildPlan = {},
                )
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    /** The populated detail: an earlier run, the latest run's timeline, and the git/PR card. */
    @Test
    fun agentDetail_body() {
        capture(
            "AgentDetail_body",
            AgentDetailUiState(
                isLoading = false,
                agent = AGENT,
                newestRun = NEWEST_RUN,
                pastRuns = listOf(PAST_RUN),
                timeline = TIMELINE,
                liveRunStatus = RunStatus.FINISHED,
            ),
        )
    }

    /**
     * The Build-visible state: a finished PLAN-mode run, so the contextual "Build this plan"
     * action bar appears above the composer (canBuild == true). This is CUR-8's headline state.
     */
    @Test
    fun agentDetail_buildVisible() {
        capture(
            "AgentDetail_buildVisible",
            AgentDetailUiState(
                isLoading = false,
                agent = PLAN_AGENT,
                newestRun = PLAN_RUN,
                timeline = PLAN_TIMELINE,
                liveRunStatus = RunStatus.FINISHED,
                latestMode = AgentMode.PLAN,
            ),
        )
    }

    /** The no-runs-yet body: the empty hint prompting the user to send a follow-up. */
    @Test
    fun agentDetail_empty() {
        capture(
            "AgentDetail_empty",
            AgentDetailUiState(
                isLoading = false,
                agent = CloudAgent(
                    id = "agent_empty",
                    name = "Draft release notes",
                    status = AgentStatus.ACTIVE,
                    createdAt = "1m ago",
                    updatedAt = "1m ago",
                ),
                newestRun = null,
                timeline = emptyList(),
            ),
        )
    }

    /** The full-screen load error: the "Couldn't load agent" message with a Retry button. */
    @Test
    fun agentDetail_error() {
        capture(
            "AgentDetail_error",
            AgentDetailUiState(
                isLoading = false,
                loadError = "Couldn't reach Cursor — check your connection.",
                agent = null,
            ),
        )
    }

    private companion object {
        // updatedAt/createdAt hold literal strings on purpose: relativeTime() can't parse
        // them, so it returns them verbatim and the golden never drifts with wall-clock time.
        val AGENT = CloudAgent(
            id = "agent1",
            name = "Fix flaky login test",
            status = AgentStatus.ACTIVE,
            url = "https://cursor.com/agents/agent1",
            createdAt = "2h ago",
            updatedAt = "2h ago",
            repos = listOf(
                RepoConfig(
                    url = "https://github.com/acme/web",
                    prUrl = "https://github.com/acme/web/pull/128",
                ),
            ),
        )

        val NEWEST_RUN = Run(
            id = "run2",
            agentId = "agent1",
            status = RunStatus.FINISHED,
            createdAt = "2h ago",
            updatedAt = "2h ago",
            durationMs = 185_000L,
            result = "Pinned the test clock and awaited the token refresh; the login test now passes 50/50 runs.",
            git = RunGit(
                branches = listOf(
                    RunGitBranch(
                        repoUrl = "https://github.com/acme/web",
                        branch = "agent/fix-flaky-login",
                        prUrl = "https://github.com/acme/web/pull/128",
                    ),
                ),
            ),
        )

        val PAST_RUN = Run(
            id = "run1",
            agentId = "agent1",
            status = RunStatus.FINISHED,
            createdAt = "1d ago",
            updatedAt = "1d ago",
            durationMs = 92_000L,
            result = "Initial attempt: added a retry around the login flow.",
        )

        // A finished plan-mode agent — the state CUR-8's Build action gates on.
        val PLAN_AGENT = CloudAgent(
            id = "agent_plan",
            name = "Add offline caching",
            status = AgentStatus.ACTIVE,
            url = "https://cursor.com/agents/agent_plan",
            createdAt = "5m ago",
            updatedAt = "5m ago",
        )

        val PLAN_RUN = Run(
            id = "run_plan",
            agentId = "agent_plan",
            status = RunStatus.FINISHED,
            createdAt = "5m ago",
            updatedAt = "5m ago",
            durationMs = 64_000L,
            result = "Plan ready for review.",
        )

        val PLAN_TIMELINE = listOf(
            TimelineItem.UserPrompt("Plan how to add offline caching for the agent list."),
            TimelineItem.Thinking(
                "I'll outline the layers to touch — a Room-backed cache, a repository read-through, " +
                    "and a staleness policy — before writing any code.",
            ),
            TimelineItem.AssistantText(
                "Here's the plan:\n\n1. Add an `AgentCacheDao` + entity keyed by agent id.\n" +
                    "2. Read-through the cache in `AgentRepository`, refreshing in the background.\n" +
                    "3. Show cached results immediately, then reconcile with the live fetch.\n\n" +
                    "Tap Build to implement this.",
            ),
            TimelineItem.ResultCard(
                status = RunStatus.FINISHED,
                text = "Plan ready for review.",
                durationMs = 64_000L,
                git = null,
            ),
        )

        val TIMELINE = listOf(
            TimelineItem.UserPrompt("Fix the flaky login test that fails intermittently on CI."),
            TimelineItem.Thinking(
                "The test likely races the auth-token refresh coroutine. Let me read the test " +
                    "setup and pin the clock so the assertion waits for the refresh to settle.",
            ),
            TimelineItem.Tool(
                callId = "c1",
                name = "read_file",
                status = "completed",
                detail = "app/src/test/LoginTest.kt",
            ),
            TimelineItem.Tool(
                callId = "c2",
                name = "grep",
                status = "completed",
                detail = "\"refreshToken\"",
            ),
            TimelineItem.AssistantText(
                "Found it — the test asserted before the refresh coroutine settled. I pinned the " +
                    "test clock and awaited the refresh, then ran it 50 times to confirm.",
            ),
            TimelineItem.ResultCard(
                status = RunStatus.FINISHED,
                text = "All green: the login test now passes consistently.",
                durationMs = 185_000L,
                git = null,
            ),
        )
    }
}
