package com.nononsense.vla.model

sealed interface TunnelState {
    data object Disconnected : TunnelState
    data class Connecting(val roomKey: String) : TunnelState
    data class Connected(
        val roomKey: String,
        val address: String,
        val shareUrl: String? = null,
        val users: List<RoomUser> = emptyList(),
        val presenceStatus: String = "Connecting to room roster…",
        val passwordPrompt: String? = null,
        val sharingError: String? = null,
    ) : TunnelState
    data class Failed(val message: String) : TunnelState
}

data class RoomUser(
    val name: String,
    val address: String,
    val state: String = "browsing",
    val pingMs: Int? = null,
    val isHost: Boolean = false,
)
