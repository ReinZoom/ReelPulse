package com.reelpulse.app.data

import java.util.Calendar

class ReelRepository(private val dao: ReelDao) {

    suspend fun recordReel(event: ReelEvent) = dao.insert(event)

    fun todayCount() = dao.countSince(startOfTodayMillis())
    suspend fun getTodayCount() = dao.getCountSince(startOfTodayMillis())
    fun todayCountByApp() = dao.countByAppSince(startOfTodayMillis())
    fun last7DaysTrend() = dao.dailyCountsSince(startOfTodayMillis() - 7L * 86_400_000)

    suspend fun clearTodayCount() = dao.deleteSince(startOfTodayMillis())

    fun getRecentHistory(limit: Int = 50) = dao.getRecentHistory(limit)

    private fun startOfTodayMillis(): Long {
        val cal = Calendar.getInstance()
        cal.set(Calendar.HOUR_OF_DAY, 0)
        cal.set(Calendar.MINUTE, 0)
        cal.set(Calendar.SECOND, 0)
        cal.set(Calendar.MILLISECOND, 0)
        return cal.timeInMillis
    }
}
