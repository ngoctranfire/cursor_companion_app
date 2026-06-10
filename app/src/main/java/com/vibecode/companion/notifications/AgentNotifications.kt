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

    fun ensureChannel(context: Context) {
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Agent status",
            NotificationManager.IMPORTANCE_HIGH,
        ).apply {
            description = "Alerts when a cloud agent run finishes or fails"
        }
        manager.createNotificationChannel(channel)
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

        // Class-name intent keeps this file decoupled from MainActivity.
        val contentIntent = Intent()
            .setClassName(context, "com.vibecode.companion.MainActivity")
            .putExtra("agentId", agentId)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        val contentPending = PendingIntent.getActivity(
            context,
            agentId.hashCode(),
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
            val prPending = PendingIntent.getActivity(
                context,
                agentId.hashCode() + 1,
                Intent(Intent.ACTION_VIEW, Uri.parse(prUrl)),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            )
            builder.addAction(0, "Open PR", prPending)
        }

        NotificationManagerCompat.from(context).notify(agentId.hashCode(), builder.build())
    }

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
