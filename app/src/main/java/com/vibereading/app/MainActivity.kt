package com.vibereading.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import com.vibereading.app.ui.navigation.AppNavigation
import com.vibereading.app.ui.theme.VibeReadingTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            VibeReadingTheme {
                AppNavigation()
            }
        }
    }
}
