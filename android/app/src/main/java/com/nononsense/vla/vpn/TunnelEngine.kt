package com.nononsense.vla.vpn

import android.os.ParcelFileDescriptor
import com.nononsense.vla.model.RoomKey

data class TunnelConfiguration(
    val address: String,
    val hostAddress: String,
    val prefixLength: Int,
    val route: String,
    val routePrefixLength: Int,
    val mtu: Int,
)

interface TunnelEngine {
    interface Listener {
        fun onConfigured(configuration: TunnelConfiguration)
        fun onFailure(message: String)
    }

    fun connect(roomKey: RoomKey, listener: Listener)
    fun attachInterface(fileDescriptor: ParcelFileDescriptor)
    fun packetCounters(): String
    fun disconnect()
}
