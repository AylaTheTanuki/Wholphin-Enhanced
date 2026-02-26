package com.github.damontecres.wholphin.services.syncplay

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.syncPlayApi
import org.jellyfin.sdk.api.sockets.subscribe
import org.jellyfin.sdk.model.api.BufferRequestDto
import org.jellyfin.sdk.model.api.GroupInfoDto
import org.jellyfin.sdk.model.api.JoinGroupRequestDto
import org.jellyfin.sdk.model.api.NewGroupRequestDto
import org.jellyfin.sdk.model.api.SeekRequestDto
import org.jellyfin.sdk.model.api.SendCommandType
import org.jellyfin.sdk.model.api.SyncPlayCommandMessage
import org.jellyfin.sdk.model.api.SyncPlayGroupUpdateCommandMessage
import timber.log.Timber
import java.time.LocalDateTime
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SyncPlayManager @Inject constructor(
    private val apiClient: ApiClient,
) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

    private val _state = MutableStateFlow<SyncPlayState>(SyncPlayState.None)
    val state = _state.asStateFlow()

    private val _availableGroups = MutableStateFlow<List<GroupInfoDto>>(emptyList())
    val availableGroups = _availableGroups.asStateFlow()

    private val _commands = MutableSharedFlow<SyncPlayCommand>(
        replay = 0,
        extraBufferCapacity = 16
    )
    val commands = _commands.asSharedFlow()

    private var syncPlayPlaylistItemId: UUID? = null
    private val syncPlayApi = apiClient.syncPlayApi
    private val fetchGroupsMutex = Mutex()

    init {
        fetchGroups()

        // Listen for play/pause/seek/stop commands from the server
        apiClient.webSocket.subscribe<SyncPlayCommandMessage>()
            .onEach { message ->
                val data = message.data
                android.util.Log.d("WHOLPHIN_SYNCPLAY", "RAW COMMAND: ${data?.command}, positionTicks=${data?.positionTicks}")
                if (data != null) {
                    data.playlistItemId?.let {
                        android.util.Log.d("WHOLPHIN_SYNCPLAY", "Storing playlistItemId: $it")
                        syncPlayPlaylistItemId = it
                    }

                    val command = when (data.command) {
                        SendCommandType.UNPAUSE -> SyncPlayCommand(CommandType.PLAY, data.positionTicks)
                        SendCommandType.PAUSE -> SyncPlayCommand(CommandType.PAUSE)
                        SendCommandType.SEEK -> SyncPlayCommand(CommandType.SEEK, data.positionTicks)
                        SendCommandType.STOP -> SyncPlayCommand(CommandType.STOP)
                    }
                    android.util.Log.d("WHOLPHIN_SYNCPLAY", "Emitting command: ${command.type}")
                    _commands.emit(command)
                    android.util.Log.d("WHOLPHIN_SYNCPLAY", "Command emitted successfully")
                }
            }.launchIn(scope)

        // Only refresh the group list when server reports a change.
        // Use tryLock so rapid GROUP UPDATE messages don't stack up.
        apiClient.webSocket.subscribe<SyncPlayGroupUpdateCommandMessage>()
            .onEach { _ ->
                android.util.Log.d("WHOLPHIN_SYNCPLAY", "GROUP UPDATE RECEIVED")
                if (fetchGroupsMutex.tryLock()) {
                    try {
                        doFetchGroups()
                    } finally {
                        fetchGroupsMutex.unlock()
                    }
                } else {
                    android.util.Log.d("WHOLPHIN_SYNCPLAY", "Skipping fetch, already in progress")
                }
            }.launchIn(scope)
    }

    private suspend fun doFetchGroups() {
        try {
            val groups = syncPlayApi.syncPlayGetGroups().content
            _availableGroups.value = groups
            val currentState = _state.value
            if (currentState is SyncPlayState.InGroup) {
                val myGroup = groups.find { it.groupId.toString() == currentState.groupId }
                if (myGroup != null) {
                    _state.value = currentState.copy(
                        userCount = myGroup.participants?.size ?: 1
                    )
                }
            }
        } catch (e: Exception) {
            Timber.e(e, "Failed to fetch SyncPlay groups")
        }
    }

    fun fetchGroups() {
        scope.launch {
            if (fetchGroupsMutex.tryLock()) {
                try {
                    doFetchGroups()
                } finally {
                    fetchGroupsMutex.unlock()
                }
            }
        }
    }

    fun createGroup() {
        scope.launch {
            _state.value = SyncPlayState.Joining
            try {
                syncPlayApi.syncPlayCreateGroup(NewGroupRequestDto(groupName = "Wholphin Group"))
                _state.value = SyncPlayState.InGroup("Group", true)
                fetchGroups()
            } catch (e: Exception) {
                Timber.e(e, "Failed to create SyncPlay group")
                _state.value = SyncPlayState.None
            }
        }
    }

    fun joinGroup(groupId: String) {
        scope.launch {
            _state.value = SyncPlayState.Joining
            try {
                syncPlayApi.syncPlayJoinGroup(JoinGroupRequestDto(groupId = UUID.fromString(groupId)))
                _state.value = SyncPlayState.InGroup(groupId, false)
                fetchGroups()
            } catch (e: Exception) {
                Timber.e(e, "Failed to join SyncPlay group")
                _state.value = SyncPlayState.None
            }
        }
    }

    fun leaveGroup() {
        scope.launch {
            try {
                syncPlayApi.syncPlayLeaveGroup()
            } catch (e: Exception) {
                Timber.w(e, "Error leaving SyncPlay group")
            } finally {
                _state.value = SyncPlayState.None
                syncPlayPlaylistItemId = null
                fetchGroups()
            }
        }
    }

    fun reportPlaybackProgress(positionTicks: Long, isPaused: Boolean, playlistItemId: UUID) {
        val resolvedPlaylistItemId = syncPlayPlaylistItemId ?: playlistItemId
        android.util.Log.d(
            "WHOLPHIN_SYNCPLAY",
            "Heartbeat tick: $positionTicks (usingFallback=${syncPlayPlaylistItemId == null})"
        )
        scope.launch {
            try {
                syncPlayApi.syncPlayBuffering(
                    BufferRequestDto(
                        `when` = LocalDateTime.now(java.time.ZoneOffset.UTC),
                        positionTicks = positionTicks,
                        isPlaying = !isPaused,
                        playlistItemId = resolvedPlaylistItemId
                    )
                )
            } catch (e: Exception) {
                Timber.e(e, "SyncPlay buffering report failed")
            }
        }
    }

    fun sendSeek(positionTicks: Long) {
        scope.launch {
            try {
                syncPlayApi.syncPlaySeek(SeekRequestDto(positionTicks = positionTicks))
            } catch (e: Exception) {
                Timber.w(e, "SyncPlay seek failed")
            }
        }
    }

    fun sendPause() {
        scope.launch {
            try {
                syncPlayApi.syncPlayPause()
            } catch (e: Exception) {
                Timber.w(e, "SyncPlay pause failed")
            }
        }
    }

    fun sendPlay() {
        scope.launch {
            try {
                syncPlayApi.syncPlayUnpause()
            } catch (e: Exception) {
                Timber.w(e, "SyncPlay play failed")
            }
        }
    }
}
