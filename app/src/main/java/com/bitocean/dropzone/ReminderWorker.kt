package com.bitocean.dropzone

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit

class ReminderWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val target = ReminderTarget(inputData.getString("type") ?: return Result.success(),
            inputData.getLong("id", -1), inputData.getString("collection").orEmpty())
        val context = applicationContext
        synchronized(Store.photoLock) {
            Store(context).use { store ->
                val extras = MemoryExtras(store)
                val reminder = extras.reminder(target) ?: return Result.success()
                if (reminder.state != "pending" || reminder.version != inputData.getString("version")) return Result.success()
                if (reminder.time > System.currentTimeMillis()) return Result.retry()
                val available = when (target.kind) {
                    "milestone" -> extras.milestone(target.id) != null
                    "collection" -> target.collection.isNotBlank()
                    "memory" -> store.get(target.id)?.let {
                        val photos = PhotoLibrary(context, store)
                        photos.accessible(it) && (it.kind != "photo" || photos.readable(Uri.parse(it.uri)))
                    } == true
                    else -> false
                }
                val manager = context.getSystemService(NotificationManager::class.java)
                if (!available || !notificationsAllowed(context) || !manager.areNotificationsEnabled()) {
                    extras.setReminderState(reminder, "missed")
                    return Result.success()
                }
                manager.createNotificationChannel(NotificationChannel(CHANNEL, "记忆提醒", NotificationManager.IMPORTANCE_DEFAULT))
                if (manager.getNotificationChannel(CHANNEL).importance == NotificationManager.IMPORTANCE_NONE) {
                    extras.setReminderState(reminder, "missed")
                    return Result.success()
                }
                val intent = Intent(context, MainActivity::class.java).setAction(OPEN_ACTION)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                    .setData(Uri.Builder().scheme("dropzone").authority("reminder")
                        .appendPath(target.kind).appendPath(target.id.toString()).build())
                val open = PendingIntent.getActivity(context, 0, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
                val notification = NotificationCompat.Builder(context, CHANNEL)
                    .setSmallIcon(R.drawable.ic_note).setContentTitle("DropZone")
                    .setContentText("你有一条记忆提醒").setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                    .setContentIntent(open).setAutoCancel(true).build()
                try {
                    manager.notify(key(target), 0, notification)
                    extras.setReminderState(reminder, "sent")
                } catch (_: SecurityException) {
                    extras.setReminderState(reminder, "missed")
                }
            }
        }
        return Result.success()
    }

    companion object {
        private const val CHANNEL = "memory-reminders"
        internal const val OPEN_ACTION = "com.bitocean.dropzone.OPEN_REMINDER"
        private const val TAG = "memory-reminders"

        internal fun notificationsAllowed(context: Context) = Build.VERSION.SDK_INT < 33 ||
            context.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) == PackageManager.PERMISSION_GRANTED

        internal fun schedule(context: Context, extras: MemoryExtras, target: ReminderTarget, time: Long) {
            synchronized(Store.photoLock) {
                val reminder = Reminder(target, time, "pending", UUID.randomUUID().toString())
                extras.saveReminder(reminder, ZoneId.systemDefault().id)
                val work = OneTimeWorkRequestBuilder<ReminderWorker>()
                    .setInputData(workDataOf("type" to target.kind, "id" to target.id, "collection" to target.collection, "version" to reminder.version))
                    .setInitialDelay((time - System.currentTimeMillis()).coerceAtLeast(0), TimeUnit.MILLISECONDS)
                    .addTag(TAG).build()
                WorkManager.getInstance(context).enqueueUniqueWork(key(target), ExistingWorkPolicy.REPLACE, work)
                context.getSystemService(NotificationManager::class.java).cancel(key(target), 0)
            }
        }

        internal fun cancel(context: Context, target: ReminderTarget) {
            WorkManager.getInstance(context).cancelUniqueWork(key(target))
            context.getSystemService(NotificationManager::class.java).cancel(key(target), 0)
        }

        internal fun cancelAll(context: Context, targets: List<Reminder>) {
            WorkManager.getInstance(context).cancelAllWorkByTag(TAG)
            targets.forEach { context.getSystemService(NotificationManager::class.java).cancel(key(it.target), 0) }
        }

        private fun key(target: ReminderTarget) = "reminder-${target.kind}-${target.id}"
    }
}
