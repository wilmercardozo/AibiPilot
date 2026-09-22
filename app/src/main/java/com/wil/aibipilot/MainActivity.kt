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

    companion object {
        const val EXTRA_RUN_ROUTINE_ID = "run_routine_id"
        private const val PREFS = "aibi_pilot_prefs"
        private const val KEY_NOTIF_ASKED = "notif_perm_asked"
        private const val REQ_NOTIFICATIONS = 1001
    }

    private lateinit var vm: RobotViewModel

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        vm = viewModels<RobotViewModel>().value
        maybeRequestNotificationPermission()
        if (savedInstanceState == null) {
            intent?.getStringExtra(EXTRA_RUN_ROUTINE_ID)?.let { vm.runPendingRoutine(it) }
        }
        setContent {
            val ui by vm.ui.collectAsStateWithLifecycle()
            AibiPilotTheme(themeMode = ui.themeMode) {
                Surface(modifier = Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    AibiPilotApp(vm)
                }
            }
        }
    }

    /**
     * POST_NOTIFICATIONS (Android 13+): se pide UNA sola vez al abrir la app
     * (marcado en prefs para no molestar si el usuario lo deniega).
     */
    private fun maybeRequestNotificationPermission() {
        if (AibiNotifier.hasPermission(this)) return
        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        if (prefs.getBoolean(KEY_NOTIF_ASKED, false)) return
        prefs.edit().putBoolean(KEY_NOTIF_ASKED, true).apply()
        requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQ_NOTIFICATIONS)
    }

    override fun onResume() {
        super.onResume()
        vm.onAppForeground()
    }
}
