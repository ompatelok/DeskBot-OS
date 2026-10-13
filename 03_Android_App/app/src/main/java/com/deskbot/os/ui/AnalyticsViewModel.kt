package com.deskbot.os.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.deskbot.os.data.*
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn

class AnalyticsViewModel(database: DeskBotDatabase) : ViewModel() {
    val historyLogs: StateFlow<List<PostureEntity>> = database.postureDao()
        .getAllLogs()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val dailySummaries: StateFlow<List<DailySummaryEntity>> = database.dailySummaryDao()
        .getRecentSummaries(30)
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )

    val dailyRollups: StateFlow<List<PostureDailyRollupEntity>> = database.postureRollupDao()
        .getAllRollups()
        .stateIn(
            scope = viewModelScope,
            started = SharingStarted.WhileSubscribed(5000),
            initialValue = emptyList()
        )
}
