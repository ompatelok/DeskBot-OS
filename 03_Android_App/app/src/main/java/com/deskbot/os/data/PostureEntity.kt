package com.deskbot.os.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "posture_logs")
data class PostureEntity(
    @PrimaryKey(autoGenerate = true) val id: Int = 0,
    val timestamp: Long,
    val state: String,
    val focusScore: Int
)
