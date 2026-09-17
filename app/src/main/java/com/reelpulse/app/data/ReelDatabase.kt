package com.reelpulse.app.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(entities = [ReelEvent::class], version = 1, exportSchema = false)
abstract class ReelDatabase : RoomDatabase() {
    abstract fun reelDao(): ReelDao

    companion object {
        @Volatile
        private var INSTANCE: ReelDatabase? = null

        fun getInstance(context: Context): ReelDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    ReelDatabase::class.java,
                    "reelpulse.db"
                ).build().also { INSTANCE = it }
            }
    }
}
