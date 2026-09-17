package com.reelpulse.app.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "reel_events")
data class ReelEvent(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val packageName: String,
    val timestampMillis: Long
)
