package com.nononsense.vla.model

import android.os.Handler
import android.os.Looper
import java.util.concurrent.CopyOnWriteArraySet

object TunnelStateStore {
    private val mainHandler = Handler(Looper.getMainLooper())
    private val listeners = CopyOnWriteArraySet<(TunnelState) -> Unit>()

    @Volatile
    var current: TunnelState = TunnelState.Disconnected
        private set

    fun update(state: TunnelState) {
        current = state
        mainHandler.post { listeners.forEach { it(state) } }
    }

    fun observe(listener: (TunnelState) -> Unit) {
        listeners += listener
        listener(current)
    }

    fun removeObserver(listener: (TunnelState) -> Unit) {
        listeners -= listener
    }
}
