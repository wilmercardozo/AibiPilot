package com.wil.aibipilot

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.wil.aibipilot.ui.AibiPilotApp
import com.wil.aibipilot.ui.theme.AibiPilotTheme

class MainActivity : ComponentActivity() {
    private lateinit var vm: RobotViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        vm = viewModels<RobotViewModel>().value
        setContent {
            val ui by vm.ui.collectAsStateWithLifecycle()
            AibiPilotTheme(themeMode = ui.themeMode) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AibiPilotApp(vm)
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        vm.onAppForeground()
    }
}
