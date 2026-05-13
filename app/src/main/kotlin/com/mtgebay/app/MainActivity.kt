package com.mtgebay.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.mtgebay.app.scan.ScanScreen
import com.mtgebay.app.ui.theme.MtgEbayTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        setContent {
            MtgEbayTheme {
                AppContent()
            }
        }
    }
}

@Composable
private fun AppContent() {
    Scaffold(modifier = Modifier.fillMaxSize()) { padding ->
        ScanScreen(modifier = Modifier.padding(padding))
    }
}
