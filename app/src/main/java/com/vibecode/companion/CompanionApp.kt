package com.vibecode.companion

import android.app.Application
import com.vibecode.companion.notifications.AgentNotifications
import com.vibecode.companion.work.PollScheduler

class CompanionApp : Application() {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        AgentNotifications.ensureChannel(this)
        PollScheduler.ensureScheduled(this)
    }
}
