package app.hopnet

import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import app.hopnet.ui.HopnetRoot
import app.hopnet.ui.theme.HopnetTheme

class MainActivity : ComponentActivity() {
    /** Set when the user taps a "new message" notification. */
    private val openChat = mutableStateOf<String?>(null)
    /** Set when the user taps a "someone nearby is lost" notification. */
    private val openFindMe = mutableStateOf(false)
    /** Set when the user taps a Live Info answer or watched-alert notification. */
    private val openInfo = mutableStateOf(false)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.light(Color.TRANSPARENT, Color.TRANSPARENT),
        )
        openChat.value = intent?.getStringExtra(EXTRA_OPEN_CHAT)
        openFindMe.value = intent?.getBooleanExtra(EXTRA_OPEN_FINDME, false) == true
        openInfo.value = intent?.getBooleanExtra(EXTRA_OPEN_INFO, false) == true
        setContent {
            HopnetTheme {
                HopnetRoot(
                    openChat = openChat.value,
                    onChatOpened = { openChat.value = null },
                    openFindMe = openFindMe.value,
                    onFindMeOpened = { openFindMe.value = false },
                    openInfo = openInfo.value,
                    onInfoOpened = { openInfo.value = false },
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        intent.getStringExtra(EXTRA_OPEN_CHAT)?.let { openChat.value = it }
        if (intent.getBooleanExtra(EXTRA_OPEN_FINDME, false)) openFindMe.value = true
        if (intent.getBooleanExtra(EXTRA_OPEN_INFO, false)) openInfo.value = true
    }

    companion object {
        const val EXTRA_OPEN_CHAT = "app.hop b net.OPEN_CHAT"
        const val EXTRA_OPEN_FINDME = "app.hopnet.OPEN_FINDME"
        const val EXTRA_OPEN_INFO = "app.hopnet.OPEN_INFO"
    }
}
