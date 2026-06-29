package com.vibecode.companion.data.storage

import android.util.Log
import com.vibecode.companion.di.AppCoroutineScope
import dev.zacsweers.metro.AppScope
import dev.zacsweers.metro.Inject
import dev.zacsweers.metro.SingleIn
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * Coordinates account-scoped *fire-and-forget* writes against a sign-out wipe so the two can
 * never race into a cross-account leak.
 *
 * The problem this exists to solve: post-launch bookkeeping (prompt, run mode, launch defaults)
 * is launched from [com.vibecode.companion.ui.launch.LaunchViewModel] on a process-lifetime scope
 * **on purpose** — publishing the navigation signal pops the launch screen and clears its
 * `viewModelScope`, so those writes must outlive the ViewModel or they'd be cancelled mid-flight.
 * But if such a write is still in flight when the user signs out, the naive shape lets it write
 * the *previous* account's data back into the stores **after** [com.vibecode.companion.data
 * .storage.AccountStore.clearAccountData] has wiped them — re-opening the cross-account leak.
 *
 * The fix keeps both guarantees:
 *  - writes run on a durable scope (a child of the app scope), so a ViewModel clear can't cancel
 *    them; yet
 *  - [wipe] cancels **and joins** every in-flight write *before* running the actual wipe, so none
 *    can resurrect just-wiped data, then mints a fresh write scope for the next signed-in session.
 *
 * The child-scope swap (rather than a sticky "signed out" flag) makes this self-resetting: after
 * sign-out + a fresh sign-in the next session's writes land on the new scope and are never gated.
 */
@Inject
@SingleIn(AppScope::class)
class AccountWriteCoordinator(
    @AppCoroutineScope private val appScope: CoroutineScope,
) {

    /** Serializes [wipe] against itself and against the scope swap a concurrent wipe would do. */
    private val wipeMutex = Mutex()

    /**
     * The scope in-flight account writes run on. Its [Job] is a child of the app scope's, so it
     * dies with the process scope; [wipe] cancels *this* scope (never the app scope) and replaces
     * it. `@Volatile` because [launchWrite] reads it off the UI thread while [wipe] swaps it.
     */
    @Volatile
    private var writeScope: CoroutineScope = newWriteScope()

    private companion object {
        const val TAG = "AccountWriteCoord"
    }

    /**
     * A child-of-app scope for fire-and-forget account writes. The [CoroutineExceptionHandler] is
     * the safety net for [launchWrite]: those writes are launched without anyone awaiting them, so
     * an exception escaping the block has no `Deferred.await` to catch it. The [SupervisorJob]
     * already keeps one failing write from cancelling its siblings or this scope, but without a
     * handler the uncaught exception would still reach the thread's default handler and crash the
     * process. Logging it here keeps a best-effort write best-effort: it fails quietly, and the
     * scope (and [wipe]) keep working.
     */
    private fun newWriteScope(): CoroutineScope =
        CoroutineScope(
            appScope.coroutineContext +
                SupervisorJob(appScope.coroutineContext[Job]) +
                CoroutineExceptionHandler { _, e -> Log.w(TAG, "Account write failed", e) },
        )

    /**
     * Launches a best-effort account-scoped write that must outlive the ViewModel that started it
     * (e.g. post-launch bookkeeping after the launch screen is popped) but must **not** survive a
     * concurrent sign-out. Runs on the durable [writeScope]; [wipe] cancels that scope first, so
     * the write can never write back data the wipe just cleared.
     */
    fun launchWrite(block: suspend CoroutineScope.() -> Unit): Job = writeScope.launch(block = block)

    /**
     * Runs [block] (the actual store wipe) with sign-out and in-flight account writes mutually
     * exclusive: every pending write is cancelled and **joined** first — so none can write back
     * after the wipe — then [block] runs, then a fresh write scope is opened for the next session.
     *
     * Because the in-flight writes are drained *before* [block], the wipe always clears whatever
     * they wrote regardless of interleaving (a write either finishes before the join or is
     * cancelled by it). [block]'s exception (e.g. the `IOException` the Room wipe maps to)
     * propagates unchanged — the swap still happened, so the next session is unaffected.
     */
    suspend fun wipe(block: suspend () -> Unit) {
        wipeMutex.withLock {
            // Swap first so any write arriving during the drain/wipe targets the fresh scope (it
            // belongs to the *next* session), then drain the scope we're tearing down.
            val draining = writeScope
            writeScope = newWriteScope()
            draining.coroutineContext[Job]?.cancelAndJoin()
            block()
        }
    }
}
