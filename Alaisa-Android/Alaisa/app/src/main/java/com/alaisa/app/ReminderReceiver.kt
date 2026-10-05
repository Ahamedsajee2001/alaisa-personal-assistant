package com.alaisa.app

import android.app.*
import android.content.*
import android.os.Build
import androidx.core.app.NotificationCompat

const val CHANNEL = "alaisa_reminders"

fun createChannel(ctx: Context) {
    if (Build.VERSION.SDK_INT >= 26) {
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel(CHANNEL, "Reminders", NotificationManager.IMPORTANCE_HIGH))
    }
}

fun scheduleReminder(ctx: Context, text: String, millis: Long) {
    val i = Intent(ctx, ReminderReceiver::class.java).putExtra("text", text)
    val pi = PendingIntent.getBroadcast(ctx, (millis % Int.MAX_VALUE).toInt(), i,
        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    ctx.getSystemService(AlarmManager::class.java)
        .setAndAllowWhileIdle(AlarmManager.RTC_WAKEUP, millis, pi)
}

class ReminderReceiver : BroadcastReceiver() {
    override fun onReceive(ctx: Context, intent: Intent) {
        createChannel(ctx)
        val open = PendingIntent.getActivity(ctx, 0, Intent(ctx, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        val n = NotificationCompat.Builder(ctx, CHANNEL)
            .setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle("Alaisa")
            .setContentText(intent.getStringExtra("text") ?: "Reminder")
            .setContentIntent(open).setAutoCancel(true).build()
        try { ctx.getSystemService(NotificationManager::class.java).notify(System.currentTimeMillis().toInt(), n) } catch (e: SecurityException) {}
    }
}
