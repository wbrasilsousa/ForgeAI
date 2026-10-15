package dev.forgeai

import android.app.Application
import kotlinx.coroutines.channels.Channel

class ForgeApp : Application() {

    override fun onCreate() {
        super.onCreate()
        INSTANCE = this
        // The engine must be initialized on a background thread so the native
        // library can be loaded without blocking the main thread.
        Thread {
            LlamaEngine.init(this)
        }.start()
    }

    companion object {
        lateinit var INSTANCE: ForgeApp
            private set
    }
}
