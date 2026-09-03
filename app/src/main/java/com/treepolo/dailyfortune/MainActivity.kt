package com.treepolo.dailyfortune

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import com.treepolo.dailyfortune.ui.DailyFortuneRoot
import com.treepolo.dailyfortune.ui.FortuneViewModel

class MainActivity : ComponentActivity() {
    private val fortuneViewModel: FortuneViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            DailyFortuneRoot(fortuneViewModel)
        }
    }

    override fun onStart() {
        super.onStart()
        fortuneViewModel.onForeground()
    }

    override fun onStop() {
        fortuneViewModel.onBackground()
        super.onStop()
    }
}
