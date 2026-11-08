package com.videoforge.ai.processing

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.WorkManager

/**
 * Explicit, app-private notification action that cancels the active unique video job.
 */
class VideoProcessingCancelReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != ACTION_CANCEL) return
        WorkManager.getInstance(context.applicationContext)
            .cancelUniqueWork(VideoProcessingWorker.TAG)
    }

    companion object {
        const val ACTION_CANCEL = "com.videoforge.ai.action.CANCEL_VIDEO_PROCESSING"
    }
}
