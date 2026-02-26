package com.github.damontecres.wholphin.services.syncplay

sealed class SyncPlayState {
    object None : SyncPlayState()
    object Joining : SyncPlayState()
    data class InGroup(
        val groupId: String,
        val isHost: Boolean,
        val userCount: Int = 0
    ) : SyncPlayState()
}

data class SyncPlayCommand(
    val type: CommandType,
    val positionTicks: Long? = null,
    val whenAtTicks: Long? = null,
    val groupId: String? = null
)

enum class CommandType {
    PLAY,
    PAUSE,
    SEEK,
    WAIT,
    STOP,
    SYNC
}