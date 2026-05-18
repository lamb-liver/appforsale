package com.lambliver.appforsale.ui

import android.os.Bundle as AndroidBundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.lambliver.appforsale.ui.theme.AppforsaleTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: AndroidBundle?) {
        super.onCreate(savedInstanceState)
        UmpMobileAdsBootstrap.attach(this)
        setContent {
            AppforsaleTheme {
                PosApp()
            }
        }
    }
}
