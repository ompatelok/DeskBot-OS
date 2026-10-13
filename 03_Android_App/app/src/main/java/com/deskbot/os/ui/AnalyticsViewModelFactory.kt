package com.deskbot.os.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.deskbot.os.data.DeskBotDatabase

class AnalyticsViewModelFactory(private val database: DeskBotDatabase) : ViewModelProvider.Factory {
    @Suppress("UNCHECKED_CAST")
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(AnalyticsViewModel::class.java)) {
            return AnalyticsViewModel(database) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class")
    }
}
