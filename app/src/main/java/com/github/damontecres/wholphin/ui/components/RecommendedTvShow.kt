package com.github.damontecres.wholphin.ui.components

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.datastore.core.DataStore
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.FavoriteWatchManager
import com.github.damontecres.wholphin.services.LatestNextUpService
import com.github.damontecres.wholphin.services.MediaReportService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.SuggestionService
import com.github.damontecres.wholphin.services.SuggestionsResource
import com.github.damontecres.wholphin.services.WatchedEventBus
import com.github.damontecres.wholphin.ui.SlimItemFields
import com.github.damontecres.wholphin.ui.data.RowColumn
import com.github.damontecres.wholphin.ui.setValueOnMain
import com.github.damontecres.wholphin.ui.toBaseItems
import com.github.damontecres.wholphin.util.ExceptionHandler
import com.github.damontecres.wholphin.util.GetItemsRequestHandler
import com.github.damontecres.wholphin.util.GetNextUpRequestHandler
import com.github.damontecres.wholphin.util.GetResumeItemsRequestHandler
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import com.github.damontecres.wholphin.util.LoadingState
import com.github.damontecres.wholphin.ui.AspectRatio
import com.github.damontecres.wholphin.ui.Cards
import com.github.damontecres.wholphin.preferences.PrefContentScale
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.ui.components.ViewOptionImageType
import dagger.assisted.Assisted
import dagger.assisted.AssistedFactory
import dagger.assisted.AssistedInject
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.api.request.GetNextUpRequest
import org.jellyfin.sdk.model.api.request.GetResumeItemsRequest
import timber.log.Timber
import java.util.UUID

@HiltViewModel(assistedFactory = RecommendedTvShowViewModel.Factory::class)
class RecommendedTvShowViewModel
@AssistedInject
constructor(
    @ApplicationContext context: Context,
    api: ApiClient,
    serverRepository: ServerRepository,
    private val preferencesDataStore: DataStore<AppPreferences>,
    private val lastestNextUpService: LatestNextUpService,
    private val suggestionService: SuggestionService,
    @Assisted val parentId: UUID,
    navigationManager: NavigationManager,
    favoriteWatchManager: FavoriteWatchManager,
    mediaReportService: MediaReportService,
    backdropService: BackdropService,
    watchedEventBus: WatchedEventBus,
) : RecommendedViewModel(
    context,
    navigationManager,
    favoriteWatchManager,
    mediaReportService,
    backdropService,
    watchedEventBus,
    api,
    serverRepository,
) {
    @AssistedFactory
    interface Factory {
        fun create(parentId: UUID): RecommendedTvShowViewModel
    }

    private val continueWatchingOptions = HomeRowViewOptions(
        heightDp = Cards.HEIGHT_EPISODE,
        aspectRatio = AspectRatio.WIDE,
        imageType = ViewOptionImageType.PRIMARY,
        contentScale = PrefContentScale.CROP,
        useSeries = false,
        episodeAspectRatio = AspectRatio.WIDE,
        episodeImageType = ViewOptionImageType.PRIMARY,
        episodeContentScale = PrefContentScale.CROP
    )

    private val nextUpOptions = HomeRowViewOptions(
        heightDp = Cards.HEIGHT_EPISODE,
        aspectRatio = AspectRatio.WIDE,
        imageType = ViewOptionImageType.PRIMARY,
        contentScale = PrefContentScale.FIT,
        useSeries = true
    )

    override val rows =
        MutableStateFlow<List<HomeRowLoadingState>>(
            listOf(
                HomeRowLoadingState.Pending(context.getString(R.string.continue_watching)), // 0
                HomeRowLoadingState.Pending(context.getString(R.string.next_up)),           // 1
                HomeRowLoadingState.Pending("Favorite Shows"),                              // 2
                HomeRowLoadingState.Pending("Favorite Episodes"),                           // 3
                HomeRowLoadingState.Pending(context.getString(R.string.suggestions)),       // 4
                HomeRowLoadingState.Pending(context.getString(R.string.top_unwatched)),     // 5
                HomeRowLoadingState.Pending("Sci-Fi Shows"),                                // 6
                HomeRowLoadingState.Pending("Horror Shows"),                                // 7
                HomeRowLoadingState.Pending("Thriller Shows"),                              // 8
                HomeRowLoadingState.Pending("Animation Shows"),                             // 9
                HomeRowLoadingState.Pending("Mystery Shows"),                               // 10
                HomeRowLoadingState.Pending("Romance Shows"),                               // 11
                HomeRowLoadingState.Pending("Action Shows"),                                // 12
                HomeRowLoadingState.Pending("Adventure Shows"),                             // 13
                HomeRowLoadingState.Pending("Comedy Shows"),                                // 14
                HomeRowLoadingState.Pending("Crime Shows"),                                 // 15
                HomeRowLoadingState.Pending("Documentary Shows"),                           // 16
                HomeRowLoadingState.Pending("Drama Shows"),                                 // 17
                HomeRowLoadingState.Pending("Family Shows"),                                // 18
                HomeRowLoadingState.Pending("Fantasy Shows"),                               // 19
                HomeRowLoadingState.Pending("History Shows"),                               // 20
                HomeRowLoadingState.Pending("War Shows"),                                   // 21
                HomeRowLoadingState.Pending("Western Shows"),                               // 22
                HomeRowLoadingState.Pending(context.getString(R.string.recently_released)), // 23
                HomeRowLoadingState.Pending(context.getString(R.string.recently_added))    // 24
            )
        )

    override fun favoritesRowIndices(): List<Int> = listOf(2, 3)

    // FIX: Re-fetches only Continue Watching (row 0) and Next Up (row 1) and
    // updates them in place — all other rows are left untouched so they don't
    // reload or flicker. This is called every ON_RESUME from RecommendedContent.
    override fun refreshWatchingRows() {
        viewModelScope.launch(Dispatchers.IO + ExceptionHandler()) {
            try {
                val preferences = preferencesDataStore.data.firstOrNull() ?: AppPreferences.getDefaultInstance()
                val combineNextUp = preferences.homePagePreferences.combineContinueNext
                val itemsPerRow = preferences.homePagePreferences.maxItemsPerRow
                val userId = serverRepository.currentUser.value?.id

                val resumeItemsDeferred = async(Dispatchers.IO) {
                    val request = GetResumeItemsRequest(
                        userId = userId,
                        parentId = parentId,
                        fields = SlimItemFields,
                        includeItemTypes = listOf(BaseItemKind.EPISODE),
                        enableUserData = true,
                        startIndex = 0,
                        limit = itemsPerRow,
                        enableTotalRecordCount = false,
                    )
                    GetResumeItemsRequestHandler.execute(api, request).toBaseItems(api, false)
                }

                val nextUpItemsDeferred = async(Dispatchers.IO) {
                    val request = GetNextUpRequest(
                        userId = userId,
                        fields = SlimItemFields,
                        imageTypeLimit = 1,
                        parentId = parentId,
                        limit = itemsPerRow,
                        enableResumable = false,
                        enableUserData = true,
                        enableRewatching = preferences.homePagePreferences.enableRewatchingNextUp,
                    )
                    GetNextUpRequestHandler.execute(api, request).toBaseItems(api, true)
                }

                val resumeItems = resumeItemsDeferred.await()
                val nextUpItems = nextUpItemsDeferred.await()

                if (combineNextUp) {
                    val combined = lastestNextUpService.buildCombined(resumeItems, nextUpItems)
                    rows.update { current ->
                        current.toMutableList().apply {
                            set(0, HomeRowLoadingState.Success(
                                context.getString(R.string.continue_watching),
                                combined,
                                viewOptions = continueWatchingOptions
                            ))
                        }
                    }
                } else {
                    rows.update { current ->
                        current.toMutableList().apply {
                            set(0, HomeRowLoadingState.Success(
                                context.getString(R.string.continue_watching),
                                resumeItems,
                                viewOptions = continueWatchingOptions
                            ))
                            set(1, HomeRowLoadingState.Success(
                                context.getString(R.string.next_up),
                                nextUpItems,
                                viewOptions = nextUpOptions
                            ))
                        }
                    }
                }
            } catch (ex: Exception) {
                Timber.e(ex, "Exception refreshing TV watching rows")
            }
        }
    }

    override fun reloadFavoritesRows() {
        viewModelScope.launch(Dispatchers.IO + ExceptionHandler()) {
            try {
                val request = GetItemsRequest(
                    parentId = parentId,
                    fields = SlimItemFields,
                    includeItemTypes = listOf(BaseItemKind.SERIES),
                    recursive = true,
                    enableUserData = true,
                    isFavorite = true,
                    sortBy = listOf(ItemSortBy.RANDOM),
                    enableTotalRecordCount = false,
                )
                val items = GetItemsRequestHandler.execute(api, request).toBaseItems(api, false)
                    .distinctBy { it.id }
                val successState = HomeRowLoadingState.Success("Favorite Shows", items)
                rows.update { current -> current.toMutableList().apply { set(2, successState) } }
            } catch (ex: Exception) {
                Timber.e(ex, "Exception reloading favorite shows row")
            }

            try {
                val request = GetItemsRequest(
                    parentId = parentId,
                    fields = SlimItemFields,
                    includeItemTypes = listOf(BaseItemKind.EPISODE),
                    recursive = true,
                    enableUserData = true,
                    isFavorite = true,
                    sortBy = listOf(ItemSortBy.RANDOM),
                    enableTotalRecordCount = false,
                )
                val items = GetItemsRequestHandler.execute(api, request).toBaseItems(api, false)
                    .distinctBy { it.id }
                val successState = HomeRowLoadingState.Success("Favorite Episodes", items, viewOptions = continueWatchingOptions)
                rows.update { current -> current.toMutableList().apply { set(3, successState) } }
            } catch (ex: Exception) {
                Timber.e(ex, "Exception reloading favorite episodes row")
            }
        }
    }

    override fun refreshRecentRows() {
        viewModelScope.launch(Dispatchers.IO + ExceptionHandler()) {
            try {
                val itemsPerRow =
                    preferencesDataStore.data.firstOrNull()?.homePagePreferences?.maxItemsPerRow
                        ?: AppPreferences.getDefaultInstance().homePagePreferences.maxItemsPerRow

                val request = GetItemsRequest(
                    parentId = parentId,
                    fields = SlimItemFields,
                    includeItemTypes = listOf(BaseItemKind.EPISODE),
                    recursive = true,
                    enableUserData = true,
                    sortBy = listOf(ItemSortBy.DATE_CREATED),
                    sortOrder = listOf(SortOrder.DESCENDING),
                    startIndex = 0,
                    limit = itemsPerRow,
                    enableTotalRecordCount = false,
                )
                val items = GetItemsRequestHandler.execute(api, request).toBaseItems(api, true)
                val successState = HomeRowLoadingState.Success(context.getString(R.string.recently_added), items)
                update(R.string.recently_added, successState)
            } catch (ex: Exception) {
                Timber.e(ex, "Exception refreshing recently added shows row")
            }
        }
    }

    override fun init() {
        viewModelScope.launch(Dispatchers.IO + ExceptionHandler()) {
            val preferences =
                preferencesDataStore.data.firstOrNull() ?: AppPreferences.getDefaultInstance()
            val combineNextUp = preferences.homePagePreferences.combineContinueNext
            val itemsPerRow = preferences.homePagePreferences.maxItemsPerRow
            val userId = serverRepository.currentUser.value?.id
            try {
                val resumeItemsDeferred =
                    async(Dispatchers.IO) {
                        val resumeItemsRequest =
                            GetResumeItemsRequest(
                                userId = userId,
                                parentId = parentId,
                                fields = SlimItemFields,
                                includeItemTypes = listOf(BaseItemKind.EPISODE),
                                enableUserData = true,
                                startIndex = 0,
                                limit = itemsPerRow,
                                enableTotalRecordCount = false,
                            )
                        GetResumeItemsRequestHandler
                            .execute(api, resumeItemsRequest)
                            .toBaseItems(api, false)
                    }

                val nextUpItemsDeferred =
                    async(Dispatchers.IO) {
                        val nextUpRequest =
                            GetNextUpRequest(
                                userId = userId,
                                fields = SlimItemFields,
                                imageTypeLimit = 1,
                                parentId = parentId,
                                limit = itemsPerRow,
                                enableResumable = false,
                                enableUserData = true,
                                enableRewatching = preferences.homePagePreferences.enableRewatchingNextUp,
                            )
                        GetNextUpRequestHandler
                            .execute(api, nextUpRequest)
                            .toBaseItems(api, true)
                    }

                val resumeItems = resumeItemsDeferred.await()
                val nextUpItems = nextUpItemsDeferred.await()

                if (combineNextUp) {
                    val combined = lastestNextUpService.buildCombined(resumeItems, nextUpItems)
                    update(
                        R.string.continue_watching,
                        HomeRowLoadingState.Success(
                            context.getString(R.string.continue_watching),
                            combined,
                            viewOptions = continueWatchingOptions
                        ),
                    )
                } else {
                    update(
                        R.string.continue_watching,
                        HomeRowLoadingState.Success(
                            context.getString(R.string.continue_watching),
                            resumeItems,
                            viewOptions = continueWatchingOptions
                        ),
                    )
                    update(
                        R.string.next_up,
                        HomeRowLoadingState.Success(
                            context.getString(R.string.next_up),
                            nextUpItems,
                            viewOptions = nextUpOptions
                        ),
                    )
                }

                if (resumeItems.isNotEmpty() || nextUpItems.isNotEmpty()) {
                    loading.setValueOnMain(LoadingState.Success)
                }
            } catch (ex: Exception) {
                Timber.e(ex, "Exception fetching tv recommendations")
                withContext(Dispatchers.Main) {
                    loading.value = LoadingState.Error(ex)
                }
            }

            val jobs = mutableListOf<Deferred<HomeRowLoadingState>>()

            val favShowsJob = viewModelScope.async(Dispatchers.IO) {
                try {
                    val request = GetItemsRequest(
                        parentId = parentId,
                        fields = SlimItemFields,
                        includeItemTypes = listOf(BaseItemKind.SERIES),
                        recursive = true,
                        enableUserData = true,
                        isFavorite = true,
                        sortBy = listOf(ItemSortBy.RANDOM),
                        enableTotalRecordCount = false,
                    )
                    val items = GetItemsRequestHandler.execute(api, request).toBaseItems(api, false)
                        .distinctBy { it.id }
                    val successState = HomeRowLoadingState.Success("Favorite Shows", items)
                    rows.update { current -> current.toMutableList().apply { set(2, successState) } }
                    successState
                } catch (ex: Exception) {
                    val errorState = HomeRowLoadingState.Error(title = "Favorite Shows", exception = ex)
                    rows.update { current -> current.toMutableList().apply { set(2, errorState) } }
                    errorState
                }
            }
            jobs.add(favShowsJob)

            val favEpisodesJob = viewModelScope.async(Dispatchers.IO) {
                try {
                    val request = GetItemsRequest(
                        parentId = parentId,
                        fields = SlimItemFields,
                        includeItemTypes = listOf(BaseItemKind.EPISODE),
                        recursive = true,
                        enableUserData = true,
                        isFavorite = true,
                        sortBy = listOf(ItemSortBy.RANDOM),
                        enableTotalRecordCount = false,
                    )
                    val items = GetItemsRequestHandler.execute(api, request).toBaseItems(api, false)
                        .distinctBy { it.id }
                    val successState = HomeRowLoadingState.Success("Favorite Episodes", items, viewOptions = continueWatchingOptions)
                    rows.update { current -> current.toMutableList().apply { set(3, successState) } }
                    successState
                } catch (ex: Exception) {
                    val errorState = HomeRowLoadingState.Error(title = "Favorite Episodes", exception = ex)
                    rows.update { current -> current.toMutableList().apply { set(3, errorState) } }
                    errorState
                }
            }
            jobs.add(favEpisodesJob)

            val topUnwatchedJob = viewModelScope.async(Dispatchers.IO) {
                try {
                    val request = GetItemsRequest(
                        parentId = parentId,
                        fields = SlimItemFields,
                        includeItemTypes = listOf(BaseItemKind.SERIES),
                        recursive = true,
                        enableUserData = true,
                        isPlayed = false,
                        sortBy = listOf(ItemSortBy.RANDOM),
                        startIndex = 0,
                        limit = itemsPerRow,
                        enableTotalRecordCount = false,
                    )
                    val items = GetItemsRequestHandler.execute(api, request).toBaseItems(api, true)
                    val successState = HomeRowLoadingState.Success(context.getString(R.string.top_unwatched), items)
                    update(R.string.top_unwatched, successState)
                    successState
                } catch (ex: Exception) {
                    val errorState = HomeRowLoadingState.Error(title = context.getString(R.string.top_unwatched), exception = ex)
                    update(R.string.top_unwatched, errorState)
                    errorState
                }
            }
            jobs.add(topUnwatchedJob)

            val genresToAdd = listOf(
                Triple("Sci-Fi",       6,  listOf("Sci-Fi", "Science Fiction")),
                Triple("Horror",       7,  listOf("Horror")),
                Triple("Thriller",     8,  listOf("Thriller")),
                Triple("Animation",    9,  listOf("Animation")),
                Triple("Mystery",      10, listOf("Mystery")),
                Triple("Romance",      11, listOf("Romance")),
                Triple("Action",       12, listOf("Action")),
                Triple("Adventure",    13, listOf("Adventure")),
                Triple("Comedy",       14, listOf("Comedy")),
                Triple("Crime",        15, listOf("Crime")),
                Triple("Documentary",  16, listOf("Documentary")),
                Triple("Drama",        17, listOf("Drama")),
                Triple("Family",       18, listOf("Family")),
                Triple("Fantasy",      19, listOf("Fantasy")),
                Triple("History",      20, listOf("History")),
                Triple("War",          21, listOf("War")),
                Triple("Western",      22, listOf("Western"))
            )

            genresToAdd.forEach { (genreTitle, slotIndex, genreQueryList) ->
                val genreJob = viewModelScope.async(Dispatchers.IO) {
                    try {
                        val request = GetItemsRequest(
                            parentId = parentId,
                            fields = SlimItemFields,
                            includeItemTypes = listOf(BaseItemKind.SERIES),
                            recursive = true,
                            enableUserData = true,
                            genres = genreQueryList,
                            sortBy = listOf(ItemSortBy.RANDOM),
                            startIndex = 0,
                            limit = itemsPerRow,
                            enableTotalRecordCount = false,
                        )
                        val items = GetItemsRequestHandler.execute(api, request).toBaseItems(api, false)
                        val successState = HomeRowLoadingState.Success("$genreTitle Shows", items)
                        rows.update { current -> current.toMutableList().apply { set(slotIndex, successState) } }
                        successState
                    } catch (ex: Exception) {
                        val errorState = HomeRowLoadingState.Error(title = "$genreTitle Shows", exception = ex)
                        rows.update { current -> current.toMutableList().apply { set(slotIndex, errorState) } }
                        errorState
                    }
                }
                jobs.add(genreJob)
            }

            val recentlyReleasedJob = viewModelScope.async(Dispatchers.IO) {
                try {
                    val request = GetItemsRequest(
                        parentId = parentId,
                        fields = SlimItemFields,
                        includeItemTypes = listOf(BaseItemKind.EPISODE),
                        recursive = true,
                        enableUserData = true,
                        sortBy = listOf(ItemSortBy.PREMIERE_DATE),
                        sortOrder = listOf(SortOrder.DESCENDING),
                        startIndex = 0,
                        limit = itemsPerRow,
                        enableTotalRecordCount = false,
                    )
                    val items = GetItemsRequestHandler.execute(api, request).toBaseItems(api, true)
                    val successState = HomeRowLoadingState.Success(context.getString(R.string.recently_released), items)
                    update(R.string.recently_released, successState)
                    successState
                } catch (ex: Exception) {
                    val errorState = HomeRowLoadingState.Error(title = context.getString(R.string.recently_released), exception = ex)
                    update(R.string.recently_released, errorState)
                    errorState
                }
            }
            jobs.add(recentlyReleasedJob)

            val recentlyAddedJob = viewModelScope.async(Dispatchers.IO) {
                try {
                    val request = GetItemsRequest(
                        parentId = parentId,
                        fields = SlimItemFields,
                        includeItemTypes = listOf(BaseItemKind.EPISODE),
                        recursive = true,
                        enableUserData = true,
                        sortBy = listOf(ItemSortBy.DATE_CREATED),
                        sortOrder = listOf(SortOrder.DESCENDING),
                        startIndex = 0,
                        limit = itemsPerRow,
                        enableTotalRecordCount = false,
                    )
                    val items = GetItemsRequestHandler.execute(api, request).toBaseItems(api, true)
                    val successState = HomeRowLoadingState.Success(context.getString(R.string.recently_added), items)
                    update(R.string.recently_added, successState)
                    successState
                } catch (ex: Exception) {
                    val errorState = HomeRowLoadingState.Error(title = context.getString(R.string.recently_added), exception = ex)
                    update(R.string.recently_added, errorState)
                    errorState
                }
            }
            jobs.add(recentlyAddedJob)

            viewModelScope.launch(Dispatchers.IO) {
                try {
                    suggestionService.getSuggestionsFlow(parentId, BaseItemKind.SERIES).collect { resource ->
                        val state = when (resource) {
                            is SuggestionsResource.Loading -> HomeRowLoadingState.Loading(context.getString(R.string.suggestions))
                            is SuggestionsResource.Success -> HomeRowLoadingState.Success(context.getString(R.string.suggestions), resource.items)
                            is SuggestionsResource.Empty -> HomeRowLoadingState.Success(context.getString(R.string.suggestions), emptyList())
                        }
                        update(R.string.suggestions, state)
                    }
                } catch (ex: Exception) {
                    update(R.string.suggestions, HomeRowLoadingState.Error(title = context.getString(R.string.suggestions), exception = ex))
                }
            }

            if (loading.value == LoadingState.Loading || loading.value == LoadingState.Pending) {
                for (i in 0..<jobs.size) {
                    val result = jobs[i].await()
                    if (result is HomeRowLoadingState.Success) {
                        loading.setValueOnMain(LoadingState.Success)
                        break
                    }
                }
            }
        }
    }

    override fun update(@StringRes title: Int, row: HomeRowLoadingState): HomeRowLoadingState {
        rows.update { current -> current.toMutableList().apply { set(rowTitles[title]!!, row) } }
        return row
    }

    companion object {
        private val rowTitles = mapOf(
            R.string.continue_watching to 0,
            R.string.next_up to 1,
            R.string.suggestions to 4,
            R.string.top_unwatched to 5,
            R.string.recently_released to 23,
            R.string.recently_added to 24
        )
    }
}

@Composable
fun RecommendedTvShow(
    preferences: UserPreferences,
    parentId: UUID,
    onFocusPosition: (RowColumn) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecommendedTvShowViewModel = hiltViewModel<RecommendedTvShowViewModel, RecommendedTvShowViewModel.Factory>(creationCallback = { it.create(parentId) }),
) {
    RecommendedContent(preferences = preferences, viewModel = viewModel, onFocusPosition = onFocusPosition, modifier = modifier)
}
