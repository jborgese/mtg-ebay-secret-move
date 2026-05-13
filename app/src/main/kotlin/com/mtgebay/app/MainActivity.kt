package com.mtgebay.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.mtgebay.app.scan.ScanScreen
import com.mtgebay.app.ui.search.ManualSearchScreen
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

private enum class Tab(val label: String) { Scan("Scan"), Search("Search") }

@Composable
private fun AppContent() {
    var tab by rememberSaveable { mutableStateOf(Tab.Scan) }
    Scaffold(
        modifier = Modifier.fillMaxSize(),
        bottomBar = {
            NavigationBar {
                NavigationBarItem(
                    selected = tab == Tab.Scan,
                    onClick = { tab = Tab.Scan },
                    icon = { Icon(Icons.Filled.QrCodeScanner, contentDescription = null) },
                    label = { Text(Tab.Scan.label) },
                )
                NavigationBarItem(
                    selected = tab == Tab.Search,
                    onClick = { tab = Tab.Search },
                    icon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    label = { Text(Tab.Search.label) },
                )
            }
        },
    ) { padding ->
        when (tab) {
            Tab.Scan -> ScanScreen(modifier = Modifier.padding(padding))
            Tab.Search -> ManualSearchScreen(modifier = Modifier.padding(padding))
        }
    }
}
