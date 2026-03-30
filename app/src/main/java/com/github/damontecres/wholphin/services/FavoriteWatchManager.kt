package com.github.damontecres.wholphin.services

import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.playStateApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.UserItemDataDto
import java.util.UUID
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class FavoriteWatchManager
    @Inject
    constructor(
        private val api: ApiClient,
        private val datePlayedService: DatePlayedService,
        private val favoritesEventBus: FavoritesEventBus,
        private val watchedEventBus: WatchedEventBus,
    ) {
        suspend fun setWatched(
            itemId: UUID,
            played: Boolean,
        ): UserItemDataDto {
            datePlayedService.invalidate(itemId)
            val result = if (played) {
                api.playStateApi.markPlayedItem(itemId).content
            } else {
                api.playStateApi.markUnplayedItem(itemId).content
            }
            watchedEventBus.emit(WatchedEvent(itemId, played))
            return result
        }

        suspend fun setFavorite(
            itemId: UUID,
            favorite: Boolean,
        ): UserItemDataDto {
            val result = if (favorite) {
                api.userLibraryApi.markFavoriteItem(itemId).content
            } else {
                api.userLibraryApi.unmarkFavoriteItem(itemId).content
            }
            favoritesEventBus.emit(FavoriteEvent(itemId, favorite))
            return result
        }
    }
