package com.vibecode.companion.ui.detail

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.unit.dp
import com.github.takahirom.roborazzi.captureRoboImage
import com.vibecode.companion.data.api.Run
import com.vibecode.companion.data.api.RunStatus
import com.vibecode.companion.ui.theme.CompanionTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot regression test for [PastRunCard]'s header row. The "View steps"
 * button used to wrap to two lines (ballooning the card) when the metadata took
 * up the row's width; this golden proves it stays on a single line.
 *
 * Renders off-device via Roborazzi + Robolectric (native graphics). Record the
 * golden with `./gradlew :app:recordRoborazziDebug`; CI/verify compares against it
 * with `./gradlew :app:verifyRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class PastRunCardScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    /**
     * Asserts that the "View steps" action stays on a single line (so the card
     * keeps its one-row height) when [PastRunCard] is rendered at its real
     * on-screen width — the layout that previously wrapped the button to two
     * lines. Compares the render against the committed golden.
     */
    @Test
    fun pastRunCard_viewStepsButton_staysSingleLine() {
        composeRule.setContent {
            CompanionTheme {
                // 328dp == a 360dp phone minus the detail list's 16dp side padding,
                // i.e. the real measured width of the card on screen. That leaves the
                // header Row ~300dp, the width at which the unfixed button wrapped.
                Box(
                    modifier = Modifier
                        .testTag(CARD_TAG)
                        .background(MaterialTheme.colorScheme.background)
                        .padding(16.dp)
                        .width(328.dp),
                ) {
                    PastRunCard(run = SCREENSHOT_RUN, onViewSteps = {})
                }
            }
        }

        composeRule.onNodeWithTag(CARD_TAG)
            .captureRoboImage("src/test/screenshots/PastRunCard_viewSteps.png")
    }

    /**
     * Verifies that a constrained [PastRunCard] moves the duration as a whole
     * metadata item instead of ellipsizing it into a partial value.
     */
    @Test
    fun pastRunCard_narrowWidth_keepsDurationWhole() {
        composeRule.setContent {
            CompanionTheme {
                Box(
                    modifier = Modifier
                        .testTag(CARD_TAG)
                        .background(MaterialTheme.colorScheme.background)
                        .padding(16.dp)
                        .width(300.dp),
                ) {
                    PastRunCard(run = SCREENSHOT_RUN, onViewSteps = {})
                }
            }
        }

        composeRule.onNodeWithTag(CARD_TAG)
            .captureRoboImage("src/test/screenshots/PastRunCard_narrowDuration.png")
    }

    private companion object {
        const val CARD_TAG = "past_run_card"

        // Deterministic on purpose: relativeTime() falls back to the raw string when
        // it can't parse an Instant, and formatDuration(65_000) is a fixed "1m 05s",
        // so the golden never drifts with wall-clock time.
        val SCREENSHOT_RUN = Run(
            id = "run_fixture",
            agentId = "agent_fixture",
            status = RunStatus.FINISHED,
            createdAt = "5m ago",
            updatedAt = "5m ago",
            durationMs = 65_000L,
        )
    }
}
