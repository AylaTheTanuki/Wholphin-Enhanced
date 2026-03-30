package com.github.damontecres.wholphin.services

import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FavoritesEventBus @Inject constructor() {
    private val _events = MutableSharedFlow<FavoriteEvent>(extraBufferCapacity = 16)
    val events: SharedFlow<FavoriteEvent> = _events.asSharedFlow()

    suspend fun emit(event: FavoriteEvent) {
        _events.emit(event)
    }
}

data class FavoriteEvent(
    val itemId: java.util.UUID,
    val isFavorite: Boolean,
)