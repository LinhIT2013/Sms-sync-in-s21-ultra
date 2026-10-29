package com.lcn.smssyncmobile

import android.content.Context
import android.os.Build
import androidx.work.Worker
import androidx.work.WorkerParameters
import com.google.android.gms.tasks.Tasks
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.firestore.FirebaseFirestore

class SmsSyncWorker(context: Context, params: WorkerParameters) : Worker(context, params) {
    override fun doWork(): Result {
        val uid = FirebaseAuth.getInstance().currentUser?.uid ?: return Result.failure()
        val sender = inputData.getString("sender") ?: return Result.failure()
        val body = inputData.getString("body") ?: return Result.failure()
        val timestamp = inputData.getLong("timestamp", System.currentTimeMillis())

        val docId = "${timestamp}_${sender.hashCode()}"
        val smsData = hashMapOf(
            "sender" to sender,
            "body" to body,
            "timestamp" to timestamp,
            "deviceModel" to "${Build.MANUFACTURER} ${Build.MODEL}",
            "syncedAt" to System.currentTimeMillis()
        )

        return try {
            val task = FirebaseFirestore.getInstance()
                .collection("users")
                .document(uid)
                .collection("sms_list")
                .document(docId)
                .set(smsData)
            Tasks.await(task)
            Result.success()
        } catch (e: Exception) {
            Result.retry()
        }
    }
}
