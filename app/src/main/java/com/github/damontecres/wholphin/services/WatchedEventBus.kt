package com.github.damontecres.wholphin.services

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class WatchedEventBus
    @Inject
    constructor() {
        private val _events = MutableSharedFlow<WatchedEvent>(extraBufferCapacity = 16)
        val events: SharedFlow<WatchedEvent> = _events.asSharedFlow()

        suspend fun emit(event: WatchedEvent) {
            _events.emit(event)
        }
    }

data class WatchedEvent(
    val itemId: UUID,
    val isPlayed: Boolean,
)
