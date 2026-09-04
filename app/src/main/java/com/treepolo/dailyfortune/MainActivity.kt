package com.treepolo.dailyfortune

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.treepolo.dailyfortune.ads.AdMobRewardedController
import com.treepolo.dailyfortune.ui.DailyFortuneRoot
import com.treepolo.dailyfortune.ui.FortuneViewModel
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {
    private val fortuneViewModel: FortuneViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        AdMobRewardedController.attach(this)
        enableEdgeToEdge()
        setContent {
            DailyFortuneRoot(fortuneViewModel)
        }
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                fortuneViewModel.adsConfig.collect { config ->
                    // Disabled means literally no ad/UMP SDK work. When remotely enabled, consent,
                    // SDK initialization and preload happen off the visual startup critical path.
                    AdMobRewardedController.preload(config, fortuneViewModel::recordAdRuntimeEvent)
                }
            }
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

    override fun onDestroy() {
        AdMobRewardedController.detach(this)
        super.onDestroy()
    }
}
