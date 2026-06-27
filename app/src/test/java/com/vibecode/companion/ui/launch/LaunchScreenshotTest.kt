package com.vibecode.companion.ui.launch

import androidx.compose.material3.SnackbarHostState
import androidx.compose.runtime.remember
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.vibecode.companion.data.api.ModelListItem
import com.vibecode.companion.ui.theme.CompanionTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot regression tests for [LaunchContent] in its empty default and a
 * fully-filled state. Each renders the stateless content from a fixture
 * [LaunchUiState] so the whole launch screen is captured deterministically.
 *
 * Renders off-device via Roborazzi + Robolectric (native graphics). Record the
 * goldens with `./gradlew :app:recordRoborazziDebug`; CI/verify compares against
 * them with `./gradlew :app:verifyRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class LaunchScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** Renders [state] through [LaunchContent] and captures the full screen to [name]. */
    private fun capture(name: String, state: LaunchUiState) {
        composeRule.setContent {
            CompanionTheme {
                LaunchContent(
                    state = state,
                    snackbarHostState = remember { SnackbarHostState() },
                    onBack = {},
                    onPromptChange = {},
                    onVoiceResult = {},
                    onSelectRepo = {},
                    onRefreshRepos = {},
                    onSelectModel = {},
                    onAutoCreatePrChange = {},
                    onPlanModeChange = {},
                    onLaunch = {},
                )
            }
        }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    /** The default state: empty prompt, no repo selected, Default model, launch disabled. */
    @Test
    fun launch_default() {
        capture("Launch_default", LaunchUiState())
    }

    /** A filled-in state: prompt typed, repo + model chosen, launch enabled. */
    @Test
    fun launch_filled() {
        capture(
            "Launch_filled",
            LaunchUiState(
                repoUrls = listOf("https://github.com/acme/web"),
                selectedRepo = "https://github.com/acme/web",
                models = listOf(
                    ModelListItem(id = "claude-opus", displayName = "Claude Opus 4.8"),
                    ModelListItem(id = "gpt-5", displayName = "GPT-5"),
                ),
                selectedModelId = "claude-opus",
                prompt = "Add a dark mode toggle to settings and persist the choice.",
                autoCreatePr = true,
                planMode = false,
            ),
        )
    }
}
