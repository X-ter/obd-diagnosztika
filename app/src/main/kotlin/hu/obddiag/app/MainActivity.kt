package hu.obddiag.app

import android.os.Bundle
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import hu.obddiag.app.data.AppSettings
import hu.obddiag.app.ui.nav.AppRoot
import hu.obddiag.app.ui.theme.ObdTheme
import hu.obddiag.core.connection.ConnectionState

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as ObdApp).container
        setContent {
            val settings by container.settings.settings.collectAsStateWithLifecycle(initialValue = AppSettings())
            val state by container.connection.state.collectAsStateWithLifecycle()
            val recording by container.logging.recording.collectAsStateWithLifecycle()
            // Csatlakoztatott járműnél (vagy naplózás közben) a kijelző ébren tartása, ha a felhasználó ezt kérte.
            val keepOn = settings.keepScreenOn && (state is ConnectionState.Connected || recording != null)
            LaunchedEffect(keepOn) {
                if (keepOn) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
                else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
            }
            ObdTheme(settings.theme) { AppRoot() }
        }
    }
}
