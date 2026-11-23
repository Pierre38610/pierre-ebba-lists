package com.pierreebba.sharedlists

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.time.DayOfWeek
import java.time.LocalDate
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import java.time.temporal.IsoFields
import java.util.concurrent.TimeUnit

class PartnerSyncWorker(
    appContext: Context,
    params: WorkerParameters
) : Worker(appContext, params) {

    override fun doWork(): Result {
        return try {
            NoteFetcher.refreshLatestNote(applicationContext)
            NoteWidgetProvider.updateAllWidgets(applicationContext)
            if (AppPrefs.isLivePushEnabled(applicationContext)) {
                NoteFetcher.pollMissedNotifications(applicationContext)
                checkSundayQuizReminder(applicationContext)
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    companion object {
        private const val WORK_NAME = "pe_partner_sync_periodic"

        fun checkSundayQuizReminder(context: Context) {
            if (!AppPrefs.isLivePushEnabled(context)) return
            val today = LocalDate.now()
            if (today.dayOfWeek != DayOfWeek.SUNDAY) return
            if (LocalTime.now().hour < 9) return

            val todayKey = today.format(DateTimeFormatter.ISO_LOCAL_DATE)
            val weekYear = today.get(IsoFields.WEEK_BASED_YEAR)
            val weekNum = today.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR)
            val weekKey = String.format("%d-W%02d", weekYear, weekNum)

            if (AppPrefs.hasSentSundayQuizNotif(context, todayKey)) return
            if (AppPrefs.getWeeklyQuizDoneWeekKey(context) == weekKey) return

            AppPrefs.markSundayQuizNotifSent(context, todayKey)
            val partner = AppPrefs.getPartnerName(context)
            NotificationHelper.showPartnerNotification(
                context = context,
                notifId = "sunday_quiz_$todayKey",
                title = "🏆 Sunday Quiz Day!",
                body = "Today’s Weekly Language Quiz is open all day! Test what $partner taught you.",
                tab = "quiz",
                deduplicate = true
            )
        }

        fun schedule(context: Context) {
            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val request = PeriodicWorkRequestBuilder<PartnerSyncWorker>(15, TimeUnit.MINUTES)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniquePeriodicWork(
                WORK_NAME,
                ExistingPeriodicWorkPolicy.KEEP,
                request
            )
        }
    }
}
