package app.hopnet

import android.app.Application
import app.hopnet.sdk.Hopnet
import app.hopnet.services.findme.FindMeRepository
import app.hopnet.services.info.InfoRepository
import app.hopnet.services.messaging.ChatRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/** App-wide singletons. Kept deliberately small: one repository per service. */
object AppGraph {
    lateinit var chat: ChatRepository
        private set
    lateinit var findMe: FindMeRepository
        private set
    lateinit var info: InfoRepository
        private set

    internal fun init(app: Application) {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        chat = ChatRepository(app, scope).also { it.start() }
        findMe = FindMeRepository(app, scope).also { it.start() }
        info = InfoRepository(app, scope).also { it.start() }
    }
}

class HopnetApp : Application() {
    override fun onCreate() {
        super.onCreate()
        Hopnet.init(this)
        AppGraph.init(this)
    }
}
