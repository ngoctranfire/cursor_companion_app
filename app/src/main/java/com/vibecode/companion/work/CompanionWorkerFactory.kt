package com.vibecode.companion.work

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn

/**
 * Custom [WorkerFactory] that builds Metro-injected workers.
 *
 * WorkManager instantiates workers reflectively via its default factory, which only knows the
 * `(Context, WorkerParameters)` constructor — it can't supply [AgentPollWorker]'s injected
 * dependencies. So [com.vibecode.companion.CompanionApp] installs this factory (via
 * `Configuration.Provider`) and we delegate to the assisted [AgentPollWorker.Factory].
 *
 * Returning `null` for unknown classes lets WorkManager fall back to its default factory. If
 * more workers appear, prefer a `Map<String, ChildWorkerFactory>` multibinding keyed by worker
 * class name over growing this `when`.
 */
@Inject
@SingleIn(AppScope::class)
class CompanionWorkerFactory(
    private val agentPollWorkerFactory: AgentPollWorker.Factory,
) : WorkerFactory() {

    /**
     * Builds a Metro-injected worker for [workerClassName], or `null` to defer to WorkManager's
     * default factory for any class this factory doesn't recognize.
     */
    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? = when (workerClassName) {
        AgentPollWorker::class.java.name ->
            agentPollWorkerFactory.create(appContext, workerParameters)
        else -> null
    }
}
