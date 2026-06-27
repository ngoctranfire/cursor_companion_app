package com.vibecode.companion

import android.app.Application
import androidx.work.Configuration
import com.vibecode.companion.di.AppGraph
import com.vibecode.companion.notifications.AgentNotifications
import com.vibecode.companion.work.PollScheduler
import dev.zacsweers.metro.createGraphFactory

class CompanionApp : Application(), Configuration.Provider {

    /** Root Metro graph for the process. Created in [onCreate], before anything reads it. */
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = createGraphFactory<AppGraph.Factory>().create(this)
        AgentNotifications.ensureChannel(this)
        PollScheduler.ensureScheduled(this)
    }

    /**
     * On-demand WorkManager init using Metro's worker factory. Requires the default
     * `WorkManagerInitializer` to be removed in the manifest (otherwise WorkManager
     * auto-initializes with the default factory and ignores this config), which would
     * leave [com.vibecode.companion.work.AgentPollWorker]'s injected constructor
     * un-instantiable.
     */
    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder()
            .setWorkerFactory(graph.workerFactory)
            .build()
}
