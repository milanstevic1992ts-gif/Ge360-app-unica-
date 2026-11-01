package it.edilmilan.ge360.agenda

import android.Manifest
import android.app.AlarmManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build

/**
 * One inexact alarm for the earliest pending reminder. No exact-alarm permission,
 * background service or network connection is required.
 */
object AgendaReminderScheduler {
    const val ACTION_DUE = "it.edilmilan.ge360.agenda.REMINDER_DUE"
    private const val CHANNEL = "ge360_agenda_reminders"
    private const val REQUEST = 76231

    fun notificationsAllowed(context: Context): Boolean {
        val manager = context.getSystemService(NotificationManager::class.java) ?: return false
        return manager.areNotificationsEnabled() &&
            (Build.VERSION.SDK_INT < 33 ||
                context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED)
    }

    fun reschedule(context: Context) {
        val alarm = context.getSystemService(AlarmManager::class.java) ?: return
        val pending = PendingIntent.getBroadcast(context, REQUEST,
            Intent(context, AgendaReminderReceiver::class.java).setAction(ACTION_DUE),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        alarm.cancel(pending)
        if (!notificationsAllowed(context)) return
        val nearest = AgendaStore(context).list().asSequence()
            .filter { it.kind != AgendaEntry.NOTE && !it.completed &&
                it.reminderEnabled && it.dueAt > 0 && it.alertedDueAt != it.dueAt }
            .map { it.dueAt - it.reminderLeadMinutes * 60_000L }
            .minOrNull() ?: return
        alarm.setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP,
            maxOf(nearest, System.currentTimeMillis() + 3_000L), pending)
    }

    fun dispatch(context: Context) {
        if (!notificationsAllowed(context)) { reschedule(context); return }
        val due = AgendaStore(context).claimDueAlerts(System.currentTimeMillis())
        val manager = context.getSystemService(NotificationManager::class.java) ?: return
        if (due.isNotEmpty()) manager.createNotificationChannel(NotificationChannel(
            CHANNEL, "Promemoria GE360", NotificationManager.IMPORTANCE_DEFAULT))
        due.take(10).forEach { item ->
            val privateItem = item.scope == AgendaEntry.PRIVATE
            val click = PendingIntent.getActivity(context, item.id.hashCode(),
                Intent(context, AgendaActivity::class.java)
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val neutral = "Apri GE360 per consultare il promemoria."
            val publicVersion = Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle("GE360 · Promemoria")
                .setContentText(neutral).build()
            val notification = Notification.Builder(context, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_popup_reminder)
                .setContentTitle(if (privateItem) "GE360 · Promemoria privato" else "GE360 · Promemoria")
                .setContentText(if (privateItem) neutral else item.title)
                .setContentIntent(click)
                .setAutoCancel(true)
                .setVisibility(Notification.VISIBILITY_PRIVATE)
                .setPublicVersion(publicVersion)
                .build()
            manager.notify(item.id.hashCode(), notification)
        }
        reschedule(context)
    }
}

class AgendaReminderReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        // Never reset an unreadable archive just because the OS delivered a broadcast.
        runCatching {
            if (intent?.action == AgendaReminderScheduler.ACTION_DUE) {
                AgendaReminderScheduler.dispatch(context)
            } else {
                AgendaReminderScheduler.reschedule(context)
            }
        }
    }
}
