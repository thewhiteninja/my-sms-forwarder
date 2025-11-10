package com.example.mysmsforwarder

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.provider.Telephony
import android.telephony.SmsManager
import androidx.core.app.NotificationCompat
import com.example.mysmsforwarder.data.AppDatabase
import com.example.mysmsforwarder.data.ForwardingHistory
import com.example.mysmsforwarder.data.Logger
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

    private fun forwardSms(
        context: Context,
        destinationNumber: String,
        originalSender: String,
        message: String
    ): Boolean {
        return try {
            Logger.d("SmsReceiver", "Attempting to send SMS to: $destinationNumber")
            val smsManager = context.getSystemService(SmsManager::class.java)
            val forwardedMessage = "SMS from $originalSender:\n$message"

            Logger.i("SmsReceiver", "Message length: ${forwardedMessage.length} characters")

            // Diviser le message si nécessaire
            val parts = smsManager.divideMessage(forwardedMessage)
            val partsCount = parts.size

            if (partsCount > 1) {
                Logger.w("SmsReceiver", "Message split into $partsCount parts")
            }

            // Créer les listes de PendingIntents pour chaque partie
            val sentIntents = ArrayList<PendingIntent>()
            val deliveredIntents = ArrayList<PendingIntent>()

            for (i in 0 until partsCount) {
                val sentIntent = Intent("SMS_SENT_$i")
                val deliveredIntent = Intent("SMS_DELIVERED_$i")

                sentIntents.add(
                    PendingIntent.getBroadcast(
                        context,
                        System.currentTimeMillis().toInt() + i,
                        sentIntent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
                    )
                )

                deliveredIntents.add(
                    PendingIntent.getBroadcast(
                        context,
                        System.currentTimeMillis().toInt() + partsCount + i,
                        deliveredIntent,
                        PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
                    )
                )
            }

            // Compteur pour suivre combien de parties ont été envoyées
            var sentPartsCount = 0
            var deliveredPartsCount = 0

            // Register receivers for sent status
            for (i in 0 until partsCount) {
                val sentReceiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        sentPartsCount++
                        when (resultCode) {
                            android.app.Activity.RESULT_OK -> {
                                Logger.i(
                                    "SmsReceiver",
                                    "SMS part ${i + 1}/$partsCount sent successfully"
                                )
                                if (sentPartsCount == partsCount) {
                                    Logger.i(
                                        "SmsReceiver",
                                        "All SMS parts sent successfully to $destinationNumber"
                                    )
                                }
                            }

                            SmsManager.RESULT_ERROR_GENERIC_FAILURE -> {
                                Logger.e(
                                    "SmsReceiver",
                                    "Part ${i + 1}/$partsCount failed: RESULT_ERROR_GENERIC_FAILURE"
                                )
                            }

                            SmsManager.RESULT_ERROR_NO_SERVICE -> {
                                Logger.e(
                                    "SmsReceiver",
                                    "Part ${i + 1}/$partsCount failed: RESULT_ERROR_NO_SERVICE - No service"
                                )
                            }

                            SmsManager.RESULT_ERROR_NULL_PDU -> {
                                Logger.e(
                                    "SmsReceiver",
                                    "Part ${i + 1}/$partsCount failed: RESULT_ERROR_NULL_PDU"
                                )
                            }

                            SmsManager.RESULT_ERROR_RADIO_OFF -> {
                                Logger.e(
                                    "SmsReceiver",
                                    "Part ${i + 1}/$partsCount failed: RESULT_ERROR_RADIO_OFF - Airplane mode?"
                                )
                            }

                            SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> {
                                Logger.e(
                                    "SmsReceiver",
                                    "Part ${i + 1}/$partsCount failed: RESULT_ERROR_LIMIT_EXCEEDED"
                                )
                            }

                            SmsManager.RESULT_NETWORK_REJECT -> {
                                Logger.e(
                                    "SmsReceiver",
                                    "Part ${i + 1}/$partsCount failed: RESULT_NETWORK_REJECT"
                                )
                            }

                            SmsManager.RESULT_NETWORK_ERROR -> {
                                Logger.e(
                                    "SmsReceiver",
                                    "Part ${i + 1}/$partsCount failed: RESULT_NETWORK_ERROR"
                                )
                            }

                            SmsManager.RESULT_NO_MEMORY -> {
                                Logger.e(
                                    "SmsReceiver",
                                    "Part ${i + 1}/$partsCount failed: RESULT_NO_MEMORY"
                                )
                            }

                            SmsManager.RESULT_INVALID_SMS_FORMAT -> {
                                Logger.e(
                                    "SmsReceiver",
                                    "Part ${i + 1}/$partsCount failed: RESULT_INVALID_SMS_FORMAT"
                                )
                            }

                            SmsManager.RESULT_SYSTEM_ERROR -> {
                                Logger.e(
                                    "SmsReceiver",
                                    "Part ${i + 1}/$partsCount failed: RESULT_SYSTEM_ERROR"
                                )
                            }

                            SmsManager.RESULT_MODEM_ERROR -> {
                                Logger.e(
                                    "SmsReceiver",
                                    "Part ${i + 1}/$partsCount failed: RESULT_MODEM_ERROR"
                                )
                            }

                            SmsManager.RESULT_ENCODING_ERROR -> {
                                Logger.e(
                                    "SmsReceiver",
                                    "Part ${i + 1}/$partsCount failed: RESULT_ENCODING_ERROR"
                                )
                            }

                            SmsManager.RESULT_INVALID_SMSC_ADDRESS -> {
                                Logger.e(
                                    "SmsReceiver",
                                    "Part ${i + 1}/$partsCount failed: RESULT_INVALID_SMSC_ADDRESS"
                                )
                            }

                            SmsManager.RESULT_OPERATION_NOT_ALLOWED -> {
                                Logger.e(
                                    "SmsReceiver",
                                    "Part ${i + 1}/$partsCount failed: RESULT_OPERATION_NOT_ALLOWED"
                                )
                            }

                            SmsManager.RESULT_INTERNAL_ERROR -> {
                                Logger.e(
                                    "SmsReceiver",
                                    "Part ${i + 1}/$partsCount failed: RESULT_INTERNAL_ERROR"
                                )
                            }

                            SmsManager.RESULT_NO_RESOURCES -> {
                                Logger.e(
                                    "SmsReceiver",
                                    "Part ${i + 1}/$partsCount failed: RESULT_NO_RESOURCES"
                                )
                            }

                            SmsManager.RESULT_CANCELLED -> {
                                Logger.w(
                                    "SmsReceiver",
                                    "Part ${i + 1}/$partsCount cancelled: RESULT_CANCELLED"
                                )
                            }

                            else -> {
                                Logger.e(
                                    "SmsReceiver",
                                    "Part ${i + 1}/$partsCount failed: Unknown code $resultCode"
                                )
                            }
                        }
                        context?.unregisterReceiver(this)
                    }
                }
                context.registerReceiver(
                    sentReceiver,
                    IntentFilter("SMS_SENT_$i"),
                    Context.RECEIVER_NOT_EXPORTED
                )
            }

            // Register receivers for delivery status
            for (i in 0 until partsCount) {
                val deliveredReceiver = object : BroadcastReceiver() {
                    override fun onReceive(context: Context?, intent: Intent?) {
                        deliveredPartsCount++
                        when (resultCode) {
                            android.app.Activity.RESULT_OK -> {
                                Logger.i(
                                    "SmsReceiver",
                                    "SMS part ${i + 1}/$partsCount delivered successfully"
                                )
                                if (deliveredPartsCount == partsCount) {
                                    Logger.i(
                                        "SmsReceiver",
                                        "All SMS parts delivered successfully to $destinationNumber"
                                    )
                                }
                            }

                            android.app.Activity.RESULT_CANCELED -> {
                                Logger.w(
                                    "SmsReceiver",
                                    "SMS part ${i + 1}/$partsCount delivery failed"
                                )
                            }

                            else -> {
                                Logger.w(
                                    "SmsReceiver",
                                    "SMS part ${i + 1}/$partsCount delivery status unknown: $resultCode"
                                )
                            }
                        }
                        context?.unregisterReceiver(this)
                    }
                }
                context.registerReceiver(
                    deliveredReceiver,
                    IntentFilter("SMS_DELIVERED_$i"),
                    Context.RECEIVER_NOT_EXPORTED
                )
            }

            // Envoyer le message (divisé en plusieurs parties si nécessaire)
            smsManager.sendMultipartTextMessage(
                destinationNumber,
                null,
                parts,
                sentIntents,
                deliveredIntents
            )

            Logger.i("SmsReceiver", "SMS send request submitted to system ($partsCount part(s))")
            true
        } catch (e: SecurityException) {
            Logger.e("SmsReceiver", "Permission denied while sending SMS", e)
            false
        } catch (e: Exception) {
            Logger.e("SmsReceiver", "Error forwarding SMS: ${e.javaClass.simpleName}", e)
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