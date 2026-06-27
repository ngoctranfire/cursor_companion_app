package com.vibecode.companion.ui.onboarding

import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.vibecode.companion.data.api.ApiKeyInfo
import com.vibecode.companion.ui.theme.CompanionTheme
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Screenshot regression tests for [OnboardingContent] across its key states
 * (default, validation error, connected). Each renders the stateless content
 * from a fixture [OnboardingUiState] so the whole onboarding screen is captured
 * deterministically.
 *
 * Renders off-device via Roborazzi + Robolectric (native graphics). Record the
 * goldens with `./gradlew :app:recordRoborazziDebug`; CI/verify compares against
 * them with `./gradlew :app:verifyRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "w411dp-h891dp-xhdpi")
class OnboardingScreenshotTest {

    @get:Rule
    val composeRule = createComposeRule()

    /** Renders [content] in the app theme and captures the full screen to [name]. */
    private fun capture(name: String, content: @Composable () -> Unit) {
        composeRule.setContent { CompanionTheme { content() } }
        composeRule.onRoot().captureRoboImage("src/test/screenshots/$name.png")
    }

    /** The empty first-run state: hero, key-entry card, disabled Connect, dashboard link. */
    @Test
    fun onboarding_default() {
        capture("Onboarding_default") {
            OnboardingContent(
                state = OnboardingUiState(),
                onKeyChange = {},
                onConnect = {},
                onOpenDashboard = {},
            )
        }
    }

    /** The validation-error state: a typed key with an inline error message shown. */
    @Test
    fun onboarding_error() {
        capture("Onboarding_error") {
            OnboardingContent(
                state = OnboardingUiState(
                    key = "crsr_invalidkeyexample00000000",
                    error = "That key didn't work — check it and try again.",
                ),
                onKeyChange = {},
                onConnect = {},
                onOpenDashboard = {},
            )
        }
    }

    /** The connected state: the green "welcome" confirmation row after a valid key. */
    @Test
    fun onboarding_connected() {
        capture("Onboarding_connected") {
            OnboardingContent(
                state = OnboardingUiState(
                    key = "crsr_validkeyexample0000000000",
                    connectedInfo = ApiKeyInfo(
                        apiKeyName = "Phone",
                        createdAt = "2026-06-01T00:00:00Z",
                        userFirstName = "Ngoc",
                    ),
                ),
                onKeyChange = {},
                onConnect = {},
                onOpenDashboard = {},
            )
        }
    }
}
