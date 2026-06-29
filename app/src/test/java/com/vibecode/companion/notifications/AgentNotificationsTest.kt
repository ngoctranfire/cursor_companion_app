package com.vibecode.companion.notifications

import android.content.Context
import android.content.Intent
import com.vibecode.companion.MainActivity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class AgentNotificationsTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = RuntimeEnvironment.getApplication()
    }

    @Test
    fun agentDetailIntent_carriesAgentIdAndTargetsMainActivity() {
        val intent = AgentNotifications.agentDetailIntent(context, "agent-123")

        assertEquals("agent-123", intent.getStringExtra(AgentNotifications.EXTRA_AGENT_ID))
        assertEquals(MainActivity::class.java.name, intent.component?.className)
        assertEquals("agent-companion", intent.data?.scheme)
        assertEquals("agent-123", intent.data?.schemeSpecificPart)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP != 0)
    }
}
