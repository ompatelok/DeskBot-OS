package com.deskbot.os.data

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase

@Database(
    entities = [
        PostureEntity::class,
        SelectedConversation::class,
        AllowedAppEntity::class,
        DiscoveredConversation::class,
        DailySummaryEntity::class,
        AppBlockingRule::class,
        PostureDailyRollupEntity::class,
        WaterReminderEvent::class,
        AppLimitBreakEvent::class
    ],
    version = 7,
    exportSchema = false
)
abstract class DeskBotDatabase : RoomDatabase() {
    abstract fun postureDao(): PostureDao
    abstract fun filterDao(): FilterDao
    abstract fun dailySummaryDao(): DailySummaryDao
    abstract fun appBlockingRuleDao(): AppBlockingRuleDao
    abstract fun postureRollupDao(): PostureRollupDao
    abstract fun waterReminderDao(): WaterReminderDao
    abstract fun appLimitBreakEventDao(): AppLimitBreakEventDao

    companion object {
        @Volatile private var INSTANCE: DeskBotDatabase? = null

        fun getDatabase(context: Context): DeskBotDatabase {
            return INSTANCE ?: synchronized(this) {
                val instance = Room.databaseBuilder(
                    context.applicationContext,
                    DeskBotDatabase::class.java,
                    "deskbot_database"
                )
                .fallbackToDestructiveMigration()
                .build()
                INSTANCE = instance
                instance
            }
        }
    }
}
