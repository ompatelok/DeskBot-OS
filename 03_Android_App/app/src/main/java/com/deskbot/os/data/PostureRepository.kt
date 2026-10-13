package com.deskbot.os.data

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch

class PostureRepository(private val postureDao: PostureDao) {
    fun logPosture(state: String, score: Int) {
        CoroutineScope(Dispatchers.IO).launch {
            postureDao.insertLog(
                PostureEntity(
                    timestamp = System.currentTimeMillis(),
                    state = state,
                    focusScore = score
                )
            )
        }
    }
}
