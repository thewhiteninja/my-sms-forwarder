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

            if (forwardedMessage.length > 160) {
                Logger.w(
                    "SmsReceiver",
                    "Message is long (${forwardedMessage.length} chars), might be split"
                )
            }

            // Create PendingIntents to track SMS status
            val sentIntent = Intent("SMS_SENT")
            val deliveredIntent = Intent("SMS_DELIVERED")

            val sentPendingIntent = PendingIntent.getBroadcast(
                context,
                System.currentTimeMillis().toInt(),
                sentIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
            )

            val deliveredPendingIntent = PendingIntent.getBroadcast(
                context,
                System.currentTimeMillis().toInt() + 1,
                deliveredIntent,
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_ONE_SHOT
            )

            // Register receivers for sent and delivered status
            val sentReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (resultCode) {
                        android.app.Activity.RESULT_OK -> {
                            Logger.i("SmsReceiver", "SMS sent successfully to $destinationNumber")
                        }

                        SmsManager.RESULT_ERROR_GENERIC_FAILURE -> {
                            Logger.e(
                                "SmsReceiver",
                                "SMS send failed: RESULT_ERROR_GENERIC_FAILURE - Generic failure"
                            )
                        }

                        SmsManager.RESULT_ERROR_NO_SERVICE -> {
                            Logger.e(
                                "SmsReceiver",
                                "SMS send failed: RESULT_ERROR_NO_SERVICE - No service available"
                            )
                        }

                        SmsManager.RESULT_ERROR_NULL_PDU -> {
                            Logger.e(
                                "SmsReceiver",
                                "SMS send failed: RESULT_ERROR_NULL_PDU - Null PDU"
                            )
                        }

                        SmsManager.RESULT_ERROR_RADIO_OFF -> {
                            Logger.e(
                                "SmsReceiver",
                                "SMS send failed: RESULT_ERROR_RADIO_OFF - Radio is off (Airplane mode?)"
                            )
                        }

                        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> {
                            Logger.e(
                                "SmsReceiver",
                                "SMS send failed: RESULT_ERROR_LIMIT_EXCEEDED - SMS limit exceeded"
                            )
                        }

                        SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED -> {
                            Logger.e(
                                "SmsReceiver",
                                "SMS send failed: RESULT_ERROR_SHORT_CODE_NOT_ALLOWED - Short code not allowed"
                            )
                        }

                        SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED -> {
                            Logger.e(
                                "SmsReceiver",
                                "SMS send failed: RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED - Short code never allowed"
                            )
                        }

                        SmsManager.RESULT_RADIO_NOT_AVAILABLE -> {
                            Logger.e(
                                "SmsReceiver",
                                "SMS send failed: RESULT_RADIO_NOT_AVAILABLE - Radio not available"
                            )
                        }

                        SmsManager.RESULT_NETWORK_REJECT -> {
                            Logger.e(
                                "SmsReceiver",
                                "SMS send failed: RESULT_NETWORK_REJECT - Network rejected"
                            )
                        }

                        SmsManager.RESULT_INVALID_ARGUMENTS -> {
                            Logger.e(
                                "SmsReceiver",
                                "SMS send failed: RESULT_INVALID_ARGUMENTS - Invalid arguments"
                            )
                        }

                        SmsManager.RESULT_INVALID_STATE -> {
                            Logger.e(
                                "SmsReceiver",
                                "SMS send failed: RESULT_INVALID_STATE - Invalid state"
                            )
                        }

                        SmsManager.RESULT_NO_MEMORY -> {
                            Logger.e("SmsReceiver", "SMS send failed: RESULT_NO_MEMORY - No memory")
                        }

                        SmsManager.RESULT_INVALID_SMS_FORMAT -> {
                            Logger.e(
                                "SmsReceiver",
                                "SMS send failed: RESULT_INVALID_SMS_FORMAT - Invalid SMS format"
                            )
                        }

                        SmsManager.RESULT_SYSTEM_ERROR -> {
                            Logger.e(
                                "SmsReceiver",
                                "SMS send failed: RESULT_SYSTEM_ERROR - System error"
                            )
                        }

                        SmsManager.RESULT_MODEM_ERROR -> {
                            Logger.e(
                                "SmsReceiver",
                                "SMS send failed: RESULT_MODEM_ERROR - Modem error"
                            )
                        }

                        SmsManager.RESULT_NETWORK_ERROR -> {
                            Logger.e(
                                "SmsReceiver",
                                "SMS send failed: RESULT_NETWORK_ERROR - Network error"
                            )
                        }

                        SmsManager.RESULT_ENCODING_ERROR -> {
                            Logger.e(
                                "SmsReceiver",
                                "SMS send failed: RESULT_ENCODING_ERROR - Encoding error"
                            )
                        }

                        SmsManager.RESULT_INVALID_SMSC_ADDRESS -> {
                            Logger.e(
                                "SmsReceiver",
                                "SMS send failed: RESULT_INVALID_SMSC_ADDRESS - Invalid SMSC address"
                            )
                        }

                        SmsManager.RESULT_OPERATION_NOT_ALLOWED -> {
                            Logger.e(
                                "SmsReceiver",
                                "SMS send failed: RESULT_OPERATION_NOT_ALLOWED - Operation not allowed"
                            )
                        }

                        SmsManager.RESULT_INTERNAL_ERROR -> {
                            Logger.e(
                                "SmsReceiver",
                                "SMS send failed: RESULT_INTERNAL_ERROR - Internal error"
                            )
                        }

                        SmsManager.RESULT_NO_RESOURCES -> {
                            Logger.e(
                                "SmsReceiver",
                                "SMS send failed: RESULT_NO_RESOURCES - No resources"
                            )
                        }

                        SmsManager.RESULT_CANCELLED -> {
                            Logger.w("SmsReceiver", "SMS send cancelled: RESULT_CANCELLED")
                        }

                        SmsManager.RESULT_REQUEST_NOT_SUPPORTED -> {
                            Logger.e(
                                "SmsReceiver",
                                "SMS send failed: RESULT_REQUEST_NOT_SUPPORTED - Request not supported"
                            )
                        }

                        else -> {
                            Logger.e(
                                "SmsReceiver",
                                "SMS send failed: Unknown result code: $resultCode"
                            )
                        }
                    }
                    context?.unregisterReceiver(this)
                }
            }

            val deliveredReceiver = object : BroadcastReceiver() {
                override fun onReceive(context: Context?, intent: Intent?) {
                    when (resultCode) {
                        android.app.Activity.RESULT_OK -> {
                            Logger.i(
                                "SmsReceiver",
                                "SMS delivered successfully to $destinationNumber"
                            )
                        }

                        android.app.Activity.RESULT_CANCELED -> {
                            Logger.w(
                                "SmsReceiver",
                                "SMS delivery failed or cancelled for $destinationNumber"
                            )
                        }

                        else -> {
                            Logger.w("SmsReceiver", "SMS delivery status unknown: $resultCode")
                        }
                    }
                    context?.unregisterReceiver(this)
                }
            }

            context.registerReceiver(
                sentReceiver,
                IntentFilter("SMS_SENT"),
                Context.RECEIVER_NOT_EXPORTED
            )
            context.registerReceiver(
                deliveredReceiver,
                IntentFilter("SMS_DELIVERED"),
                Context.RECEIVER_NOT_EXPORTED
            )

            smsManager.sendTextMessage(
                destinationNumber,
                null,
                forwardedMessage,
                sentPendingIntent,
                deliveredPendingIntent
            )

            Logger.i("SmsReceiver", "SMS send request submitted to system")
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