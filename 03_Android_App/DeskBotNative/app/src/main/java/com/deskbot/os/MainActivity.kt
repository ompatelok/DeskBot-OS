package com.deskbot.os

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme {
                Surface(modifier = Modifier.fillMaxSize()) {
                    DeskBotDashboard()
                }
            }
        }
    }
}

@Composable
fun DeskBotDashboard() {
    var postureState by remember { mutableStateOf("Waiting for data...") }
    
    Column(modifier = Modifier.padding(20.dp)) {
        Text(text = "DeskBot OS", style = MaterialTheme.typography.headlineLarge)
        Spacer(modifier = Modifier.height(20.dp))
        Text(text = "Live Posture: $postureState", style = MaterialTheme.typography.bodyLarge)
    }
}