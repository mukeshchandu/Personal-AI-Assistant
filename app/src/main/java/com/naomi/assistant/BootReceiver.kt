package com.naomi.assistant

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/**
 * Re-arms the "Hey Naomi" wake listener after a reboot — but only if the user had it on.
 *
 * Android 14+ blocks starting a microphone foreground service straight from BOOT_COMPLETED
 * (the user isn't present to consent). So we *try* to start it (works on many devices,
 * especially with battery-optimization exemption + the OEM "Autostart" toggle enabled), and
 * if the OS refuses, we post a one-tap notification to resume instead of crashing.
 */
class BootReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != "android.intent.action.QUICKBOOT_POWERON" &&
            action != "com.htc.intent.action.QUICKBOOT_POWERON"
        ) return

        val wasOn = context.getSharedPreferences("naomi", Context.MODE_PRIVATE)
            .getBoolean("wake", false)
        if (!wasOn) return

        try {
            WakeService.start(context)
            android.util.Log.d("Naomi", "BootReceiver: wake service started")
        } catch (e: Exception) {
            // Mic FGS blocked at boot — show a tap-to-resume notification.
            android.util.Log.w("Naomi", "BootReceiver: direct start blocked (${e.message}); posting resume notification")
            postResumeNotification(context)
        }
    }

    private fun postResumeNotification(context: Context) {
        val nm = context.getSystemService(NotificationManager::class.java)
        val ch = "naomi_boot_resume"
        if (nm.getNotificationChannel(ch) == null) {
            nm.createNotificationChannel(
                NotificationChannel(ch, "Naomi resume after reboot", NotificationManager.IMPORTANCE_HIGH)
            )
        }
        val openIntent = Intent(context, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val pi = PendingIntent.getActivity(
            context, 3, openIntent,
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        nm.notify(3, Notification.Builder(context, ch)
            .setSmallIcon(android.R.drawable.ic_btn_speak_now)
            .setContentTitle("Tap to resume Naomi")
            .setContentText("Phone rebooted — tap to start \"Hey Naomi\" listening again.")
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build())
    }
}
