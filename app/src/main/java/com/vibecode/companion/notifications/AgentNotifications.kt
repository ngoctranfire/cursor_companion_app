package com.vibecode.companion.notifications

import android.Manifest
import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import com.vibecode.companion.data.api.RunStatus

/**
 * Local notifications for terminal agent runs (MVP — milestone 2 replaces the
 * polling source with FCM push, but the posting side stays the same).
 */
object AgentNotifications {

    const val CHANNEL_ID = "agent_status"
    const val PLAN_READY_CHANNEL_ID = "plan_ready"
    const val EXTRA_AGENT_ID = "agentId"
    private const val DETAIL_SCHEME = "agent-companion"
    private const val MAIN_ACTIVITY_CLASS = "com.vibecode.companion.MainActivity"

    /**
     * Fixed notification id — per-agent uniqueness comes from the notification
     * tag (the agent id itself), not from String.hashCode(), which can collide
     * across agents.
     */
    private const val RUN_TERMINAL_ID = 1
    private const val PLAN_READY_ID = 2

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val statusChannel = NotificationChannel(
            CHANNEL_ID,
            "Agent status",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Alerts when a cloud agent run finishes or fails"
        }
        val planReadyChannel = NotificationChannel(
            PLAN_READY_CHANNEL_ID,
            "Plan ready",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Alerts when a plan-mode agent is ready for review"
        }
        manager.createNotificationChannels(listOf(statusChannel, planReadyChannel))
    }

    /**
     * Posts a notification for a run that just reached FINISHED or ERROR.
     * No-ops when the POST_NOTIFICATIONS permission is missing (API 33+) or
     * notifications are disabled for the app.
     */
    @SuppressLint("MissingPermission")
    fun notifyRunTerminal(
        context: Context,
        agentId: String,
        agentName: String?,
        status: String,
        prUrl: String?,
    ) {
        if (!canNotify(context)) return
        ensureChannel(context)

        val text = buildString {
            append(if (status == RunStatus.ERROR) "Failed ✗" else "Finished ✓")
            if (prUrl != null) append(" — PR ready")
        }

        val contentIntent = agentDetailIntent(context, agentId)
        val contentPending = PendingIntent.getActivity(
            context,
            0,
            contentIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val builder = NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle(agentName ?: "Agent")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentPending)
            .setAutoCancel(true)

        if (prUrl != null) {
            // Distinct per PR via the ACTION_VIEW data URI — no request code needed.
            val prPending = PendingIntent.getActivity(
                context,
                0,
                Intent(Intent.ACTION_VIEW, Uri.parse(prUrl)),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, "Open PR", prPending)
        }

        NotificationManagerCompat.from(context).notify(agentId, RUN_TERMINAL_ID, builder.build())
    }

    /**
     * Posts the plan-specific notification for a plan-mode run that just reached a terminal state.
     * No-ops under the same permission/app-notification checks as generic status notifications.
     */
    @SuppressLint("MissingPermission")
    fun notifyPlanReady(
        context: Context,
        agentId: String,
        agentName: String?,
    ) {
        if (!canNotify(context)) return
        ensureChannel(context)

        val contentPending = PendingIntent.getActivity(
            context,
            0,
            agentDetailIntent(context, agentId),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

        val text = agentName?.let { "$it has a plan ready to review." }
            ?: "An agent has a plan ready to review."
        val notification = NotificationCompat.Builder(context, PLAN_READY_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Plan ready - review and build")
            .setContentText(text)
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setContentIntent(contentPending)
            .setAutoCancel(true)
            .build()

        NotificationManagerCompat.from(context).notify(agentId, PLAN_READY_ID, notification)
    }

    internal fun agentDetailIntent(context: Context, agentId: String): Intent =
        Intent()
            .setClassName(context, MAIN_ACTIVITY_CLASS)
            .setData(Uri.fromParts(DETAIL_SCHEME, agentId, null))
            .putExtra(EXTRA_AGENT_ID, agentId)
            .addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TOP or
                    Intent.FLAG_ACTIVITY_SINGLE_TOP,
            )

    private fun canNotify(context: Context): Boolean {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return false
        }
        return NotificationManagerCompat.from(context).areNotificationsEnabled()
    }
}
