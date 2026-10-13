package com.deskbot.os.data

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "allowed_apps")
data class AllowedAppEntity(
    @PrimaryKey val appPackage: String,
    val isEnabled: Boolean = true
)

@Entity(tableName = "selected_conversations")
data class SelectedConversation(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val appPackage: String,
    val conversationTitle: String,
    val matchType: String = "exact",
    val isEnabled: Boolean = true
)

@Entity(tableName = "discovered_conversations")
data class DiscoveredConversation(
    @PrimaryKey val conversationTitle: String,
    val appPackage: String,
    val lastSeenTimestamp: Long
)
