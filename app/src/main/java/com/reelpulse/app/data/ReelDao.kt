package com.reelpulse.app.data

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface ReelDao {

    @Insert
    suspend fun insert(event: ReelEvent)

    @Query("SELECT COUNT(*) FROM reel_events WHERE timestampMillis >= :since")
    fun countSince(since: Long): Flow<Int>

    @Query("SELECT COUNT(*) FROM reel_events WHERE timestampMillis >= :since")
    suspend fun getCountSince(since: Long): Int

    @Query(
        """SELECT packageName, COUNT(*) as count FROM reel_events
           WHERE timestampMillis >= :since GROUP BY packageName"""
    )
    fun countByAppSince(since: Long): Flow<List<AppCount>>

    @Query(
        """SELECT (timestampMillis / 86400000) as dayBucket, COUNT(*) as count
           FROM reel_events WHERE timestampMillis >= :since
           GROUP BY dayBucket ORDER BY dayBucket ASC"""
    )
    fun dailyCountsSince(since: Long): Flow<List<DayCount>>

    @Query("DELETE FROM reel_events WHERE timestampMillis >= :since")
    suspend fun deleteSince(since: Long)

    @Query("SELECT * FROM reel_events ORDER BY timestampMillis DESC LIMIT :limit")
    fun getRecentHistory(limit: Int): Flow<List<ReelEvent>>
}

data class AppCount(val packageName: String, val count: Int)
data class DayCount(val dayBucket: Long, val count: Int)
