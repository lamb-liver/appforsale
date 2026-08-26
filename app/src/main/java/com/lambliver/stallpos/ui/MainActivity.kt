package com.lambliver.stallpos.ui

import android.os.Bundle as AndroidBundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.lambliver.stallpos.data.SyncScheduler

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: AndroidBundle?) {
        super.onCreate(savedInstanceState)
        SyncScheduler.enqueue(this)
        setContent {
            PosApp()
        }
    }
}
