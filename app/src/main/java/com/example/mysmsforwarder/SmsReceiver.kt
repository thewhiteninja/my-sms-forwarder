package com.example.mysmsforwarder

import android.app.Activity
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsManager
import android.util.Log
import androidx.core.app.NotificationCompat
import com.example.mysmsforwarder.data.AppDatabase
import com.example.mysmsforwarder.data.ForwardingHistory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class SmsReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action == Telephony.Sms.Intents.SMS_RECEIVED_ACTION) {
            val messages = Telephony.Sms.Intents.getMessagesFromIntent(intent)
            val database = AppDatabase.getDatabase(context)

            CoroutineScope(Dispatchers.IO).launch {
                val enabledFilters = database.smsFilterDao().getEnabledFilters()

                for (smsMessage in messages) {
                    val sender = smsMessage.displayOriginatingAddress
                    val messageBody = smsMessage.messageBody

                    for (filter in enabledFilters) {
                        val matches = when {
                            filter.senderNumber.isNotEmpty() &&
                                    sender.contains(filter.senderNumber) -> true

                            filter.senderName.isNotEmpty() &&
                                    sender.contains(filter.senderName, ignoreCase = true) -> true

                            else -> false
                        }

                        if (matches) {
                            val success = forwardSms(
                                context,
                                filter.forwardToNumber,
                                sender,
                                messageBody
                            )

                            // Save to history
                            database.forwardingHistoryDao().insertHistory(
                                ForwardingHistory(
                                    filterId = filter.id,
                                    filterName = filter.name,
                                    originalSender = sender,
                                    forwardedTo = filter.forwardToNumber,
                                    messagePreview = messageBody,
                                    success = success
                                )
                            )

                            // Show notification
                            showNotification(
                                context,
                                filter.name,
                                sender,
                                filter.forwardToNumber,
                                success
                            )

                            break // Only one filter per SMS
                        }
                    }
                }
            }
        }
    }

    fun getResultCodeString(resultCode: Int): String {
        return when (resultCode) {
            Activity.RESULT_OK -> "SMS envoyé avec succès"
            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> "Erreur générique"
            SmsManager.RESULT_ERROR_RADIO_OFF -> "Radio éteinte"
            SmsManager.RESULT_ERROR_NULL_PDU -> "PDU null"
            SmsManager.RESULT_ERROR_NO_SERVICE -> "Pas de service"
            SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "Limite dépassée"
            SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED -> "Code court non autorisé"
            SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED -> "Code court jamais autorisé"
            else -> "Code inconnu: $resultCode"
        }
    }

    private fun forwardSms(
        context: Context,
        destinationNumber: String,
        originalSender: String,
        message: String
    ): Boolean {
        return try {
            val smsManager = context.getSystemService(SmsManager::class.java)
            val forwardedMessage = "SMS from $originalSender:\n$message"

            smsManager.sendTextMessage(
                destinationNumber,
                null,
                forwardedMessage,
                null,
                null
            )
            true
        } catch (e: Exception) {
            e.printStackTrace()
            Log.e("SmsReceiver", "forwardSMS failed", e)
            false
        }
    }

    private fun showNotification(
        context: Context,
        filterName: String,
        sender: String,
        forwardedTo: String,
        success: Boolean
    ) {
        val notificationManager =
            context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, "sms_forwarding")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(if (success) "SMS forwarded" else "Forwarding failed")
            .setContentText("$filterName: $sender → $forwardedTo")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()

        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
}