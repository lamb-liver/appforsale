package com.lambliver.stallpos.ui

import android.os.Bundle as AndroidBundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.lambliver.stallpos.ui.theme.StallPosTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: AndroidBundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            StallPosTheme {
                PosApp()
            }
        }
    }
}
