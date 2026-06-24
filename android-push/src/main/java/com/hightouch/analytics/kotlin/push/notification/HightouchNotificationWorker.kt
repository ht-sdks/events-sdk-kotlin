package com.hightouch.analytics.kotlin.push.notification

import android.content.Context
import androidx.work.Data
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerParameters

/**
 * Posts a rich-media (image) push notification on a WorkManager background thread.
 *
 * FCM's `onMessageReceived` runs on a binder thread with a short execution budget (~10–20s) and
 * killing the process if it overruns. Fetching an attachment over HTTP can take seconds, so for
 * messages that carry an image we hand off to this worker instead of blocking inline. Messages
 * without an image are posted synchronously by [HightouchNotificationPresenter.handle].
 */
internal class HightouchNotificationWorker(
    appContext: Context,
    params: WorkerParameters,
) : Worker(appContext, params) {

    override fun doWork(): Result {
        // WorkManager Data carries Any values; the input we enqueue is all strings (the FCM data
        // map). Filter back to the String-typed map the presenter renders.
        val data = inputData.keyValueMap.mapNotNull { (key, value) ->
            (value as? String)?.let { key to it }
        }.toMap()
        if (data.isEmpty()) return Result.failure()

        HightouchNotificationPresenter.postNotification(applicationContext, data)
        return Result.success()
    }

    companion object {
        /**
         * Enqueue a worker to render [data] (the FCM `data` map). FCM caps the payload at 4 KB,
         * well under WorkManager's 10 KB [Data] limit, so the whole map round-trips as input.
         */
        fun enqueue(context: Context, data: Map<String, String>) {
            val input = Data.Builder().apply {
                // for-loop iteration (not Map.forEach) to avoid the API-24 java.util.Map.forEach
                // binding; the minSdk is 21.
                for ((key, value) in data) putString(key, value)
            }.build()
            val request = OneTimeWorkRequestBuilder<HightouchNotificationWorker>()
                .setInputData(input)
                .build()
            WorkManager.getInstance(context).enqueue(request)
        }
    }
}
