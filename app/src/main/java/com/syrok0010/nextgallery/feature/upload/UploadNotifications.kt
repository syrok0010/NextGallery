package com.syrok0010.nextgallery.feature.upload

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.text.format.Formatter
import androidx.core.content.getSystemService
import com.syrok0010.nextgallery.AppLinks
import com.syrok0010.nextgallery.MainActivity
import com.syrok0010.nextgallery.R
import com.syrok0010.nextgallery.core.ui.asString

/** Progress and result notifications of [UploadWorker]; both open the uploads screen. */
internal class UploadNotifications(private val context: Context) {
    private val manager: NotificationManager = checkNotNull(context.getSystemService())

    fun createChannel() {
        manager.createNotificationChannel(
            NotificationChannel(
                CHANNEL_ID,
                context.getString(R.string.upload_channel),
                NotificationManager.IMPORTANCE_LOW,
            ),
        )
    }

    /** [percent] null shows an indeterminate bar. */
    fun progress(message: String, percent: Int?, detail: String? = null): Notification =
        Notification
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cloud)
            .setContentIntent(uploadsIntent())
            .setContentTitle(message)
            .setContentText(detail)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setProgress(100, percent ?: 0, percent == null)
            .build()

    fun progress(activity: UploadActivity): Notification {
        val message = context.getString(
            R.string.upload_progress,
            (activity.processed + 1).coerceAtMost(activity.total),
            activity.total,
        )
        return progress(
            message,
            activity.percent.takeUnless { activity.saving },
            transferText(activity),
        )
    }

    private fun transferText(activity: UploadActivity): String? {
        if (activity.saving) return context.getString(R.string.uploads_saving)
        val sent = activity.sentBytes ?: return null
        val sentSize = Formatter.formatShortFileSize(context, sent)
        val total = activity.totalBytes ?: return sentSize
        return context.getString(
            R.string.uploads_sent,
            sentSize,
            Formatter.formatShortFileSize(context, total),
        )
    }

    fun showProgress(activity: UploadActivity) = manager.notify(PROGRESS_ID, progress(activity))

    fun showResult(result: UploadResult) {
        val lines = result.details().map { it.asString(context) }
        val notification = Notification
            .Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_cloud)
            .setContentIntent(uploadsIntent())
            .setContentTitle(result.title().asString(context))
            .apply {
                if (lines.isNotEmpty()) {
                    setContentText(lines.joinToString(" · "))
                    setStyle(Notification.BigTextStyle().bigText(lines.joinToString("\n")))
                }
            }.setOnlyAlertOnce(true)
            .build()
        manager.notify(RESULT_ID, notification)
    }

    private fun uploadsIntent(): PendingIntent =
        PendingIntent.getActivity(
            context,
            128,
            Intent(Intent.ACTION_VIEW, AppLinks.Uploads, context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )

    companion object {
        private const val CHANNEL_ID = "cloud-upload"
        const val PROGRESS_ID = 42
        private const val RESULT_ID = 43
    }
}
