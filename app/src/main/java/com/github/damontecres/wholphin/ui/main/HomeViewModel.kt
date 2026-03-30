package com.github.damontecres.wholphin.ui.main

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.DatePlayedService
import com.github.damontecres.wholphin.services.FavoriteWatchManager
import com.github.damontecres.wholphin.services.HomePageResolvedSettings
import com.github.damontecres.wholphin.services.HomeSettingsService
import com.github.damontecres.wholphin.services.MediaReportService
import com.github.damontecres.wholphin.services.NavDrawerService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.UserPreferencesService
import com.github.damontecres.wholphin.services.tvAccess
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.showToast
import com.github.damontecres.wholphin.util.ExceptionHandler
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import com.github.damontecres.wholphin.util.LoadingState
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

@HiltViewModel
class HomeViewModel
@Inject
constructor(
    @param:ApplicationContext private val context: Context,
    val navigationManager: NavigationManager,
    val serverRepository: ServerRepository,
    val mediaReportService: MediaReportService,
    private val navDrawerService: NavDrawerService,
    private val homeSettingsService: HomeSettingsService,
    private val favoriteWatchManager: FavoriteWatchManager,
    private val datePlayedService: DatePlayedService,
    private val backdropService: BackdropService,
    private val userPreferencesService: UserPreferencesService,
    private val favoritesEventBus: com.github.damontecres.wholphin.services.FavoritesEventBus,
    private val watchedEventBus: com.github.damontecres.wholphin.services.WatchedEventBus,
    private val api: ApiClient,
) : ViewModel() {
    private val _state = MutableStateFlow(HomeState.EMPTY)
    val state: StateFlow<HomeState> = _state

    var hasInitialized = false
        private set

    // Prevents concurrent favorites row fetches from racing each other
    private val favoritesMutex = Mutex()

    init {
        datePlayedService.invalidateAll()
        // Listen for favorite changes from anywhere in the app and refresh the home favorites row.
        // NOTE: setFavorite() does NOT also call refreshFavoritesRow() directly — this bus is the
        // single source of truth for favorites row refreshes. Having both caused a double-refresh
        // race that produced JobCancellationExceptions and triggered onRestart on Shield TV.
        viewModelScope.launchIO {
            favoritesEventBus.events.collect { event ->
                Timber.d("BREADCRUMB: FAVORITES-BUS - received event for item ${event.itemId}, isFavorite=${event.isFavorite}")
                refreshFavoritesRow()
            }
        }
        viewModelScope.launchIO {
            watchedEventBus.events.collect { event ->
                Timber.d("BREADCRUMB: WATCHED-BUS - received event for item ${event.itemId}, isPlayed=${event.isPlayed}")
                refreshMovieWatchHistoryRow()
            }
        }
    }

    fun initOnce() {
        if (!hasInitialized) {
            hasInitialized = true
            init()
        }
    }

    // Only refreshes continue watching / next up rows without touching
    // favorites or genre rows — fast and non-jarring
    fun refreshWatchingRows() {
        if (!hasInitialized) return
        viewModelScope.launchIO {
            try {
                val preferences = userPreferencesService.getCurrent()
                val prefs = preferences.appPreferences.homePagePreferences
                val userDto = serverRepository.currentUserDto.value ?: return@launchIO
                val libraries = navDrawerService.getAllUserLibraries(userDto.id, userDto.tvAccess)
                val settings = homeSettingsService.currentSettings.first { it != HomePageResolvedSettings.EMPTY }

                val watchingRows = settings.rows.filter { isWatchingRow(it.config) }
                if (watchingRows.isEmpty()) return@launchIO

                val freshRows = watchingRows.map { row ->
                    homeSettingsService.fetchDataForRow(
                        row = row.config,
                        scope = viewModelScope,
                        prefs = prefs,
                        userDto = userDto,
                        libraries = libraries,
                        limit = prefs.maxItemsPerRow,
                    )
                }

                _state.update { currentState ->
                    val currentRows = currentState.homeRows.toMutableList()
                    freshRows.forEachIndexed { freshIndex, freshRow ->
                        val freshTitle = (freshRow as? HomeRowLoadingState.Success)?.title
                        val freshItems = (freshRow as? HomeRowLoadingState.Success)?.items
                        val index = currentRows.indexOfFirst {
                            it is HomeRowLoadingState.Success &&
                                    it.title == freshTitle
                        }
                        when {
                            index >= 0 -> currentRows[index] = freshRow
                            index < 0 && !freshItems.isNullOrEmpty() -> currentRows.add(freshIndex, freshRow)
                        }
                    }
                    currentState.copy(homeRows = currentRows)
                }
            } catch (ex: Exception) {
                Timber.e(ex, "Exception refreshing watching rows")
            }
        }
    }

    fun refreshMovieWatchHistoryRow() {
        if (!hasInitialized) return
        viewModelScope.launchIO {
            try {
                val preferences = userPreferencesService.getCurrent()
                val prefs = preferences.appPreferences.homePagePreferences
                val userDto = serverRepository.currentUserDto.value ?: return@launchIO
                val libraries = navDrawerService.getAllUserLibraries(userDto.id, userDto.tvAccess)
                val settings = homeSettingsService.currentSettings.first { it != HomePageResolvedSettings.EMPTY }

                val historyRow = settings.rows.firstOrNull { it.config is HomeRowConfig.MovieWatchHistory } ?: return@launchIO
                val freshRow =
                    homeSettingsService.fetchDataForRow(
                        row = historyRow.config,
                        scope = viewModelScope,
                        prefs = prefs,
                        userDto = userDto,
                        libraries = libraries,
                        limit = prefs.maxItemsPerRow,
                    )

                _state.update { currentState ->
                    val currentRows = currentState.homeRows.toMutableList()
                    val index =
                        currentRows.indexOfFirst {
                            it is HomeRowLoadingState.Success &&
                                it.title == context.getString(R.string.movie_watch_history)
                        }

                    if (index >= 0) {
                        currentRows[index] = freshRow
                        currentState.copy(homeRows = currentRows)
                    } else {
                        currentState.copy(homeRows = currentRows.apply { add(freshRow) })
                    }
                }
            } catch (ex: Exception) {
                Timber.e(ex, "Exception refreshing movie watch history row")
            }
        }
    }

    fun refreshRecentlyAddedRows() {
        if (!hasInitialized) return
        viewModelScope.launchIO {
            try {
                val preferences = userPreferencesService.getCurrent()
                val prefs = preferences.appPreferences.homePagePreferences
                val userDto = serverRepository.currentUserDto.value ?: return@launchIO
                val libraries = navDrawerService.getAllUserLibraries(userDto.id, userDto.tvAccess)
                val settings = homeSettingsService.currentSettings.first { it != HomePageResolvedSettings.EMPTY }

                val recentRows = settings.rows.filter { it.config is HomeRowConfig.RecentlyAdded }
                if (recentRows.isEmpty()) return@launchIO

                val freshRows = recentRows.map { row ->
                    homeSettingsService.fetchDataForRow(
                        row = row.config,
                        scope = viewModelScope,
                        prefs = prefs,
                        userDto = userDto,
                        libraries = libraries,
                        limit = prefs.maxItemsPerRow,
                    )
                }

                _state.update { currentState ->
                    val currentRows = currentState.homeRows.toMutableList()
                    freshRows.forEach { freshRow ->
                        val freshTitle = (freshRow as? HomeRowLoadingState.Success)?.title
                        val freshItems = (freshRow as? HomeRowLoadingState.Success)?.items
                        val index =
                            currentRows.indexOfFirst {
                                it is HomeRowLoadingState.Success && it.title == freshTitle
                            }
                        when {
                            index >= 0 -> currentRows[index] = freshRow
                            index < 0 && !freshItems.isNullOrEmpty() -> currentRows.add(freshRow)
                        }
                    }
                    currentState.copy(homeRows = currentRows)
                }
            } catch (ex: Exception) {
                Timber.e(ex, "Exception refreshing recently added home rows")
            }
        }
    }

    fun refreshFavoritesRow() {
        if (!hasInitialized) return
        viewModelScope.launchIO {
            favoritesMutex.withLock {
                Timber.d("BREADCRUMB: 6. refreshFavoritesRow triggered")
                try {
                    val preferences = userPreferencesService.getCurrent()
                    val prefs = preferences.appPreferences.homePagePreferences
                    val userDto = serverRepository.currentUserDto.value
                    if (userDto == null) {
                        Timber.w("BREADCRUMB: FAVORITES-ROW - currentUserDto is null, skipping refresh")
                        return@withLock
                    }
                    val libraries = navDrawerService.getAllUserLibraries(userDto.id, userDto.tvAccess)
                    val settings = homeSettingsService.currentSettings.first { it != HomePageResolvedSettings.EMPTY }

                    val favRowIndex = settings.rows.indexOfFirst { it.config is HomeRowConfig.MyList }
                    if (favRowIndex < 0) {
                        Timber.d("BREADCRUMB: FAVORITES-ROW - no favorites row configured, skipping")
                        return@withLock
                    }

                    val favRow = settings.rows[favRowIndex]
                    val freshFavData = homeSettingsService.fetchDataForRow(
                        row = favRow.config,
                        scope = viewModelScope,
                        prefs = prefs,
                        userDto = userDto,
                        libraries = libraries,
                        limit = prefs.maxItemsPerRow,
                    )

                    Timber.d("BREADCRUMB: 7. Fresh Favorites data received from server")

                    _state.update { currentState ->
                        val currentRows = currentState.homeRows.toMutableList()
                        val displayedIndex = currentRows.indexOfFirst {
                            it is HomeRowLoadingState.Success &&
                                    it.title == context.getString(R.string.favorites)
                        }
                        if (displayedIndex >= 0) {
                            currentRows[displayedIndex] = freshFavData
                            currentState.copy(homeRows = currentRows)
                        } else {
                            Timber.d("BREADCRUMB: FAVORITES-ROW - favorites row not found in displayed rows, no update")
                            currentState
                        }
                    }
                    Timber.d("BREADCRUMB: 8. UI State updated with fresh favorites list")
                } catch (ex: Exception) {
                    Timber.e(ex, "Exception refreshing favorites row")
                }
            }
        }
    }

    fun init() {
        viewModelScope.launchIO {
            Timber.d("init HomeViewModel")
            try {
                val preferences = userPreferencesService.getCurrent()
                val prefs = preferences.appPreferences.homePagePreferences

                serverRepository.currentUserDto.value?.let { userDto ->
                    val libraries =
                        navDrawerService.getAllUserLibraries(userDto.id, userDto.tvAccess)
                    val settings =
                        homeSettingsService.currentSettings.first { it != HomePageResolvedSettings.EMPTY }
                    val state = state.value

                    val refresh =
                        state.loadingState == LoadingState.Success && state.settings == settings

                    val semaphore = Semaphore(4)

                    val watchingRowIndexes =
                        settings.rows
                            .mapIndexedNotNull { index, row ->
                                if (isWatchingRow(row.config)) index else null
                            }
                    val deferred =
                        settings.rows
                            .sortedByDescending { isWatchingRow(it.config) }
                            .map { row ->
                                viewModelScope.async(Dispatchers.IO) {
                                    semaphore.withPermit {
                                        Timber.v("Fetching row: %s", row)
                                        try {
                                            if (row.config is HomeRowConfig.MyList) {
                                                favoritesMutex.withLock {
                                                    homeSettingsService.fetchDataForRow(
                                                        row = row.config,
                                                        scope = viewModelScope,
                                                        prefs = prefs,
                                                        userDto = userDto,
                                                        libraries = libraries,
                                                        limit = prefs.maxItemsPerRow,
                                                    )
                                                }
                                            } else {
                                                homeSettingsService.fetchDataForRow(
                                                    row = row.config,
                                                    scope = viewModelScope,
                                                    prefs = prefs,
                                                    userDto = userDto,
                                                    libraries = libraries,
                                                    limit = prefs.maxItemsPerRow,
                                                )
                                            }
                                        } catch (ex: Exception) {
                                            Timber.e(ex, "Error on row %s", row)
                                            HomeRowLoadingState.Error(row.title, exception = ex)
                                        }
                                    }
                                }
                            }

                    if (refresh && state.homeRows.isNotEmpty() && watchingRowIndexes.isNotEmpty()) {
                        Timber.v("Refreshing rows: %s", watchingRowIndexes)
                        val rows =
                            deferred
                                .filterIndexed { index, _ -> index in watchingRowIndexes }
                                .awaitAll()
                        _state.update {
                            val newRows =
                                it.homeRows.toMutableList().apply {
                                    rows.forEachIndexed { index, row ->
                                        set(watchingRowIndexes[index], row)
                                    }
                                }
                            it.copy(
                                loadingState = LoadingState.Success,
                                homeRows = newRows,
                            )
                        }
                    }
                    val rows =
                        deferred
                            .awaitAll()
                            .filter {
                                it is HomeRowLoadingState.Error ||
                                        (it is HomeRowLoadingState.Success && it.items.isNotEmpty())
                            }
                    Timber.v("Got all rows")
                    _state.update {
                        it.copy(
                            loadingState = LoadingState.Success,
                            refreshState = LoadingState.Success,
                            homeRows = rows,
                        )
                    }
                }
            } catch (ex: Exception) {
                Timber.e(ex, "Exception during home page loading")
                if (state.value.loadingState == LoadingState.Success) {
                    showToast(context, "Error refreshing home: ${ex.localizedMessage}")
                } else {
                    _state.update {
                        it.copy(loadingState = LoadingState.Error(ex))
                    }
                }
            }
        }
    }

    fun setWatched(
        itemId: UUID,
        played: Boolean,
    ) = viewModelScope.launch(ExceptionHandler() + Dispatchers.IO) {
        favoriteWatchManager.setWatched(itemId, played)
        refreshItemInRows(itemId, null)
        refreshMovieWatchHistoryRow()
    }

    fun setFavorite(
        itemId: UUID,
        favorite: Boolean,
    ) = viewModelScope.launch(ExceptionHandler() + Dispatchers.IO) {
        Timber.d("BREADCRUMB: 1. setFavorite entry for item: $itemId")
        favoriteWatchManager.setFavorite(itemId, favorite)
        Timber.d("BREADCRUMB: 2. Server confirmed favorite change")
        refreshItemInRows(itemId, favorite)
        // NOTE: Do NOT call refreshFavoritesRow() here. FavoriteWatchManager emits to
        // FavoritesEventBus after confirming the server change, and the HomeViewModel.init
        // block collects that event and calls refreshFavoritesRow() exactly once.
        // Calling it here too caused a double-refresh race (two coroutines competing for
        // favoritesMutex), JobCancellationExceptions, and a spurious onRestart on Shield TV.
        Timber.d("BREADCRUMB: setFavorite complete - favorites row refresh delegated to FavoritesEventBus")
    }

    private fun refreshItemInRows(itemId: UUID, newFavoriteStatus: Boolean?) {
        viewModelScope.launchIO {
            Timber.d("BREADCRUMB: 3. refreshItemInRows started")
            try {
                val userId = serverRepository.currentUser.value?.id
                if (userId == null) {
                    Timber.w("BREADCRUMB: refreshItemInRows - currentUser is null, skipping")
                    return@launchIO
                }
                val freshItemDto = api.userLibraryApi.getItem(userId = userId, itemId = itemId).content
                Timber.d("BREADCRUMB: 4. Fetched fresh item metadata")

                _state.update { currentState ->
                    val newRows = currentState.homeRows.map { row ->
                        if (row is HomeRowLoadingState.Success) {
                            val newItems = row.items.map { item ->
                                if (item is BaseItem && item.id == itemId) {
                                    if (newFavoriteStatus != null) {
                                        freshItemDto.userData?.let { ud ->
                                            try {
                                                val field = ud::class.java.getDeclaredField("isFavorite")
                                                field.isAccessible = true
                                                field.set(ud, newFavoriteStatus)
                                            } catch (e: Exception) {
                                                Timber.e(e, "Could not force favorite field")
                                            }
                                        }
                                    }
                                    item.copy(data = freshItemDto)
                                } else {
                                    item
                                }
                            }
                            // Keep the row structure stable here and let the favorites-event refresh
                            // perform the actual add/remove once. Eagerly removing the focused card
                            // caused TV focus to jump out of the row before restore could re-anchor it.
                            row.copy(items = newItems)
                        } else {
                            row
                        }
                    }
                    currentState.copy(homeRows = newRows)
                }
                Timber.d("BREADCRUMB: 5. All rows mapped and updated locally")
            } catch (ex: Exception) {
                Timber.e(ex, "Failed to refresh item $itemId in home rows, falling back to full reload")
                withContext(Dispatchers.Main) { init() }
            }
        }
    }

    fun updateBackdrop(item: BaseItem) {
        viewModelScope.launchIO {
            backdropService.submit(item)
        }
    }

    fun hideBackdropImage() {
        viewModelScope.launchIO {
            backdropService.hideBackdropImageKeepColors()
        }
    }
}

data class HomeState(
    val loadingState: LoadingState,
    val refreshState: LoadingState,
    val homeRows: List<HomeRowLoadingState>,
    val settings: HomePageResolvedSettings,
) {
    companion object {
        val EMPTY =
            HomeState(
                LoadingState.Pending,
                LoadingState.Pending,
                listOf(),
                HomePageResolvedSettings.EMPTY,
            )
    }
}

private fun isWatchingRow(row: HomeRowConfig) =
    row is HomeRowConfig.ContinueWatching ||
            row is HomeRowConfig.NextUp ||
            row is HomeRowConfig.ContinueWatchingCombined
