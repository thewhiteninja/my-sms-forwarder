package com.example.mysmsforwarder.data

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

object Logger {
    private var database: AppDatabase? = null

    fun init(context: Context) {
        database = AppDatabase.getDatabase(context)
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        saveLog("DEBUG", tag, message)
    }

    fun i(tag: String, message: String) {
        Log.i(tag, message)
        saveLog("INFO", tag, message)
    }

    fun w(tag: String, message: String) {
        Log.w(tag, message)
        saveLog("WARNING", tag, message)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        val fullMessage = if (throwable != null) {
            "$message\n${throwable.message}\n${throwable.stackTraceToString()}"
        } else {
            message
        }
        saveLog("ERROR", tag, fullMessage)
    }

    private fun saveLog(level: String, tag: String, message: String) {
        database?.let { db ->
            CoroutineScope(Dispatchers.IO).launch {
                try {
                    db.appLogDao().insertLog(
                        AppLog(
                            level = level,
                            tag = tag,
                            message = message
                        )
                    )
                } catch (e: Exception) {
                    Log.e("Logger", "Failed to save log to database", e)
                }
            }
        }
    }
}