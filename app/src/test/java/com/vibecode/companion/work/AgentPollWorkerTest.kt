package com.vibecode.companion.work

import com.vibecode.companion.data.api.AgentMode
import com.vibecode.companion.data.api.RunStatus
import org.junit.Assert.assertEquals
import org.junit.Test

class AgentPollWorkerTest {

    @Test
    fun terminalTransition_withPersistedPlanMode_postsPlanReadyNotification() {
        assertEquals(
            TerminalNotificationType.PLAN_READY,
            terminalNotificationType(
                priorStatus = RunStatus.RUNNING,
                currentStatus = RunStatus.FINISHED,
                updatedAt = "not-needed-for-known-prior",
                lastPollCompletedAt = null,
                persistedMode = AgentMode.PLAN,
            ),
        )
    }

    @Test
    fun terminalTransition_withPersistedAgentMode_keepsNormalTerminalNotification() {
        assertEquals(
            TerminalNotificationType.RUN_TERMINAL,
            terminalNotificationType(
                priorStatus = RunStatus.RUNNING,
                currentStatus = RunStatus.FINISHED,
                updatedAt = "not-needed-for-known-prior",
                lastPollCompletedAt = null,
                persistedMode = AgentMode.AGENT,
            ),
        )
    }

    @Test
    fun terminalTransition_withUnknownMode_keepsNormalTerminalNotification() {
        assertEquals(
            TerminalNotificationType.RUN_TERMINAL,
            terminalNotificationType(
                priorStatus = RunStatus.RUNNING,
                currentStatus = RunStatus.FINISHED,
                updatedAt = "not-needed-for-known-prior",
                lastPollCompletedAt = null,
                persistedMode = null,
            ),
        )
    }

    @Test
    fun nonTerminalTransition_postsNothing_evenWhenPersistedModeIsPlan() {
        assertEquals(
            TerminalNotificationType.NONE,
            terminalNotificationType(
                priorStatus = RunStatus.CREATING,
                currentStatus = RunStatus.RUNNING,
                updatedAt = "not-needed-for-non-terminal",
                lastPollCompletedAt = null,
                persistedMode = AgentMode.PLAN,
            ),
        )
    }
}
