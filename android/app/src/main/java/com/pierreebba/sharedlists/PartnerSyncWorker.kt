package com.pierreebba.sharedlists

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters
import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.LocalDateTime
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
                checkDailyWordReminder(applicationContext)
            }
            Result.success()
        } catch (_: Exception) {
            Result.retry()
        }
    }

    class DailyWordReminderWorker(
        appContext: Context,
        params: WorkerParameters
    ) : Worker(appContext, params) {
        override fun doWork(): Result {
            return try {
                checkDailyWordReminder(applicationContext)
                scheduleDailyWordReminder(applicationContext)
                Result.success()
            } catch (_: Exception) {
                scheduleDailyWordReminder(applicationContext)
                Result.success()
            }
        }
    }

    companion object {
        private const val WORK_NAME = "pe_partner_sync_periodic"
        private const val WORK_NAME_20H = "pe_daily_word_20h"

        fun checkDailyWordReminder(context: Context) {
            if (!AppPrefs.isLivePushEnabled(context)) return
            if (LocalTime.now().hour < 20) return

            val todayKey = LocalDate.now().format(DateTimeFormatter.ISO_LOCAL_DATE)
            if (AppPrefs.hasSentDailyWordNotif(context, todayKey)) return
            if (AppPrefs.getMyWordDoneDateKey(context) == todayKey) return

            // Double-check Supabase in case the user posted from web or another session
            if (NoteFetcher.hasTodayWordInCloud(context, todayKey) == true) {
                AppPrefs.setMyWordDoneDateKey(context, todayKey)
                return
            }

            AppPrefs.markDailyWordNotifSent(context, todayKey)
            val isEbba = AppPrefs.getCurrentUser(context) == "Ebba"
            val langName = if (isEbba) "Swedish" else "French"
            val streak = AppPrefs.getCurrentWordStreak(context)
            val streakPart = if (streak > 0) {
                " Don't lose your $streak-day streak!"
            } else {
                " Share it before midnight to keep your streak alive!"
            }
            NotificationHelper.showPartnerNotification(
                context = context,
                notifId = "daily_word_20h_$todayKey",
                title = "🔥 Daily $langName Word Reminder",
                body = "You haven't posted today's $langName word yet.$streakPart",
                tab = "words",
                deduplicate = true
            )
        }

        fun scheduleDailyWordReminder(context: Context) {
            val now = LocalDateTime.now()
            var target = now.toLocalDate().atTime(20, 0, 5)
            if (!now.isBefore(target)) {
                target = target.plusDays(1)
            }
            val delayMs = Duration.between(now, target).toMillis().coerceAtLeast(1000L)

            val request = OneTimeWorkRequestBuilder<DailyWordReminderWorker>()
                .setInitialDelay(delayMs, TimeUnit.MILLISECONDS)
                .build()

            WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(
                WORK_NAME_20H,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }

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
            scheduleDailyWordReminder(context)
        }
    }
}
