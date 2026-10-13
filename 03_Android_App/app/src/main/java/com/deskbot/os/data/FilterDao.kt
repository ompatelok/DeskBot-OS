package com.deskbot.os.data

import androidx.room.*
import kotlinx.coroutines.flow.Flow

@Dao
interface FilterDao {
    @Query("SELECT * FROM allowed_apps")
    fun getAllowedApps(): Flow<List<AllowedAppEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAllowedApp(app: AllowedAppEntity)

    @Query("SELECT * FROM selected_conversations WHERE appPackage = :pkg AND isEnabled = 1")
    fun getSelectedConversations(pkg: String): Flow<List<SelectedConversation>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertSelectedConversation(conv: SelectedConversation)

    @Delete
    suspend fun deleteSelectedConversation(conv: SelectedConversation)

    @Query("SELECT * FROM discovered_conversations ORDER BY lastSeenTimestamp DESC")
    fun getDiscoveredConversations(): Flow<List<DiscoveredConversation>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun recordDiscovered(conv: DiscoveredConversation)

    @Query("DELETE FROM discovered_conversations WHERE conversationTitle = :title")
    suspend fun removeDiscovered(title: String)

    @Query("DELETE FROM discovered_conversations WHERE lastSeenTimestamp < :cutoffTimestamp")
    suspend fun purgeOldDiscoveredConversations(cutoffTimestamp: Long)
}
