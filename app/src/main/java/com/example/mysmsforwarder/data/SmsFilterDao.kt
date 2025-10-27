package com.example.mysmsforwarder.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface SmsFilterDao {
    @Query("SELECT * FROM sms_filters ORDER BY createdAt DESC")
    fun getAllFilters(): Flow<List<SmsFilter>>

    @Query("SELECT * FROM sms_filters WHERE isEnabled = 1")
    suspend fun getEnabledFilters(): List<SmsFilter>

    @Insert
    suspend fun insertFilter(filter: SmsFilter): Long

    @Update
    suspend fun updateFilter(filter: SmsFilter)

    @Delete
    suspend fun deleteFilter(filter: SmsFilter)

    @Query("SELECT * FROM sms_filters WHERE id = :id")
    suspend fun getFilterById(id: Long): SmsFilter?
}

@Dao
interface ForwardingHistoryDao {
    @Query("SELECT * FROM forwarding_history ORDER BY timestamp DESC LIMIT 100")
    fun getRecentHistory(): Flow<List<ForwardingHistory>>

    @Insert
    suspend fun insertHistory(history: ForwardingHistory)

    @Query("DELETE FROM forwarding_history WHERE timestamp < :beforeTimestamp")
    suspend fun deleteOldHistory(beforeTimestamp: Long)
}

@Dao
interface AppLogDao {
    @Query("SELECT * FROM app_logs ORDER BY timestamp DESC LIMIT 500")
    fun getAllLogs(): Flow<List<AppLog>>

    @Insert
    suspend fun insertLog(log: AppLog)

    @Query("DELETE FROM app_logs WHERE timestamp < :beforeTimestamp")
    suspend fun deleteOldLogs(beforeTimestamp: Long)

    @Query("DELETE FROM app_logs")
    suspend fun clearAllLogs()
}