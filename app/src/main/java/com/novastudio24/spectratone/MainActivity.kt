package com.novastudio24.spectratone

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.novastudio24.spectratone.engine.NativeEngine

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            SpectraToneScreen()
        }
    }
}

@Composable
fun SpectraToneScreen() {
    var engineMsg by remember { mutableStateOf("Connecting to C++ Core...") }

    LaunchedEffect(Unit) {
        try {
            engineMsg = NativeEngine.getEngineStatus()
        } catch (e: Exception) {
            engineMsg = "Engine Error: ${e.message}"
        }
    }

    Surface(
        modifier = Modifier.fillMaxSize(),
        color = Color(0xFF0F0F14)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("SpectraTone", fontSize = 34.sp, fontWeight = FontWeight.Bold, color = Color.White)
            Text("by Nova Studio 24", fontSize = 14.sp, color = Color(0xFF00E676), modifier = Modifier.padding(bottom = 28.dp))

            Card(
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = Color(0xFF1C1C24)),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(modifier = Modifier.padding(20.dp)) {
                    Text("C++ ENGINE STATUS", fontSize = 11.sp, color = Color.Gray, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.height(6.dp))
                    Text(engineMsg, fontSize = 14.sp, color = Color(0xFFE0E0E0))
                }
            }
        }
    }
}
