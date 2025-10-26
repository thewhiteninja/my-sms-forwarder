package com.example.mysmsforwarder

import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.provider.Telephony
import android.telephony.SmsManager
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
                            
                            // Enregistrer dans l'historique
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
                            
                            // Afficher notification
                            showNotification(
                                context,
                                filter.name,
                                sender,
                                filter.forwardToNumber,
                                success
                            )
                            
                            break // Un seul filtre par SMS
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
            val smsManager = context.getSystemService(SmsManager::class.java)
            val forwardedMessage = "SMS de $originalSender:\n$message"
            
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
        val notificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        
        val intent = Intent(context, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(
            context,
            0,
            intent,
            PendingIntent.FLAG_IMMUTABLE
        )
        
        val notification = NotificationCompat.Builder(context, "sms_forwarding")
            .setSmallIcon(android.R.drawable.ic_dialog_email)
            .setContentTitle(if (success) "SMS transféré" else "Échec du transfert")
            .setContentText("$filterName: $sender → $forwardedTo")
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .setContentIntent(pendingIntent)
            .setAutoCancel(true)
            .build()
        
        notificationManager.notify(System.currentTimeMillis().toInt(), notification)
    }
}