package app.kairo.wear

import android.app.Application
import android.content.Context
import app.kairo.core.net.ClientContext
import app.kairo.core.net.KairoApi
import app.kairo.core.session.SessionStore

class WearApp : Application() {
    lateinit var session: SessionStore
        private set
    lateinit var api: KairoApi
        private set

    override fun onCreate() {
        super.onCreate()
        session = SessionStore(this)
        api = KairoApi(session, object : ClientContext {
            override val clientKind = "wear"
            override val capabilities = setOf("timer")
            override suspend fun location(): Pair<Double, Double>? = null
        })
    }
}

val Context.wear: WearApp get() = applicationContext as WearApp
