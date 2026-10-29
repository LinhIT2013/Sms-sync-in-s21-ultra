package com.lcn.smssyncmobile

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import androidx.work.Constraints
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.workDataOf

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (!SyncPrefs.isAutoSync(context)) return

        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            if (messages.isNullOrEmpty()) return

            val sender = messages[0].originatingAddress ?: "Unknown"
            val timestamp = messages[0].timestampMillis
            val fullBody = StringBuilder()
            for (sms in messages) {
                fullBody.append(sms.messageBody)
            }

            val data = workDataOf(
                "sender" to sender,
                "body" to fullBody.toString(),
                "timestamp" to timestamp
            )

            val constraints = Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .build()

            val syncRequest = OneTimeWorkRequestBuilder<SmsSyncWorker>()
                .setInputData(data)
                .setConstraints(constraints)
                .build()

            WorkManager.getInstance(context).enqueue(syncRequest)
        }
    }
}
