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
import com.github.damontecres.wholphin.preferences.AppPreference
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.FavoriteWatchManager
import com.github.damontecres.wholphin.services.MediaReportService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.SuggestionService
import com.github.damontecres.wholphin.services.SuggestionsResource
import com.github.damontecres.wholphin.ui.SlimItemFields
import com.github.damontecres.wholphin.ui.data.RowColumn
import com.github.damontecres.wholphin.ui.setValueOnMain
import com.github.damontecres.wholphin.ui.toBaseItems
import com.github.damontecres.wholphin.util.ExceptionHandler
import com.github.damontecres.wholphin.util.GetItemsRequestHandler
import com.github.damontecres.wholphin.util.GetResumeItemsRequestHandler
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import com.github.damontecres.wholphin.util.LoadingState
import com.github.damontecres.wholphin.ui.AspectRatio
import com.github.damontecres.wholphin.ui.Cards
import com.github.damontecres.wholphin.preferences.PrefContentScale
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
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
import org.jellyfin.sdk.model.api.request.GetResumeItemsRequest
import timber.log.Timber
import java.util.UUID

@HiltViewModel(assistedFactory = RecommendedMovieViewModel.Factory::class)
class RecommendedMovieViewModel
@AssistedInject
constructor(
    @ApplicationContext context: Context,
    api: ApiClient,
    serverRepository: ServerRepository,
    private val preferencesDataStore: DataStore<AppPreferences>,
    private val suggestionService: SuggestionService,
    @Assisted val parentId: UUID,
    navigationManager: NavigationManager,
    favoriteWatchManager: FavoriteWatchManager,
    mediaReportService: MediaReportService,
    backdropService: BackdropService,
) : RecommendedViewModel(
    context,
    navigationManager,
    favoriteWatchManager,
    mediaReportService,
    backdropService,
    api,
    serverRepository,
) {
    @AssistedFactory
    interface Factory {
        fun create(parentId: UUID): RecommendedMovieViewModel
    }

    private val cinematicWideOptions = HomeRowViewOptions(
        heightDp = Cards.HEIGHT_EPISODE,
        aspectRatio = AspectRatio.WIDE,
        imageType = ViewOptionImageType.THUMB,
        contentScale = PrefContentScale.CROP
    )

    override val rows =
        MutableStateFlow<List<HomeRowLoadingState>>(
            listOf(
                HomeRowLoadingState.Pending(context.getString(R.string.continue_watching)),
                HomeRowLoadingState.Pending("Favorite Movies"),
                HomeRowLoadingState.Pending(context.getString(R.string.recently_released)),
                HomeRowLoadingState.Pending(context.getString(R.string.recently_added)),
                HomeRowLoadingState.Pending(context.getString(R.string.suggestions)),
                HomeRowLoadingState.Pending(context.getString(R.string.top_unwatched)),

                HomeRowLoadingState.Pending("Sci-Fi Movies"),
                HomeRowLoadingState.Pending("Action Movies"),
                HomeRowLoadingState.Pending("Adventure Movies"),
                HomeRowLoadingState.Pending("Animation Movies"),
                HomeRowLoadingState.Pending("Comedy Movies"),
                HomeRowLoadingState.Pending("Crime Movies"),
                HomeRowLoadingState.Pending("Documentary Movies"),
                HomeRowLoadingState.Pending("Drama Movies"),
                HomeRowLoadingState.Pending("Family Movies"),
                HomeRowLoadingState.Pending("Fantasy Movies"),
                HomeRowLoadingState.Pending("History Movies"),
                HomeRowLoadingState.Pending("Horror Movies"),
                HomeRowLoadingState.Pending("Mystery Movies"),
                HomeRowLoadingState.Pending("Romance Movies"),
                HomeRowLoadingState.Pending("Thriller Movies"),
                HomeRowLoadingState.Pending("War Movies"),
                HomeRowLoadingState.Pending("Western Movies")
            )
        )

    override fun init() {
        viewModelScope.launch(Dispatchers.IO + ExceptionHandler()) {
            val itemsPerRow =
                preferencesDataStore.data
                    .firstOrNull()
                    ?.homePagePreferences
                    ?.maxItemsPerRow
                    ?: AppPreference.HomePageItems.defaultValue.toInt()
            try {
                val resumeItemsRequest =
                    GetResumeItemsRequest(
                        parentId = parentId,
                        fields = SlimItemFields,
                        includeItemTypes = listOf(BaseItemKind.MOVIE),
                        enableUserData = true,
                        startIndex = 0,
                        limit = itemsPerRow,
                        enableTotalRecordCount = false,
                    )
                val resumeItems =
                    GetResumeItemsRequestHandler
                        .execute(api, resumeItemsRequest)
                        .toBaseItems(api, false)
                update(
                    R.string.continue_watching,
                    HomeRowLoadingState.Success(
                        context.getString(R.string.continue_watching),
                        resumeItems,
                        viewOptions = cinematicWideOptions
                    ),
                )

                if (resumeItems.isNotEmpty()) {
                    loading.setValueOnMain(LoadingState.Success)
                }
            } catch (ex: Exception) {
                Timber.e(ex, "Exception fetching movie recommendations")
                withContext(Dispatchers.Main) {
                    loading.value = LoadingState.Error(ex)
                }
            }

            val jobs = mutableListOf<Deferred<HomeRowLoadingState>>()

            val favJob = viewModelScope.async(Dispatchers.IO) {
                try {
                    val request = GetItemsRequest(
                        parentId = parentId,
                        fields = SlimItemFields,
                        includeItemTypes = listOf(BaseItemKind.MOVIE),
                        recursive = true,
                        enableUserData = true,
                        isFavorite = true,
                        sortBy = listOf(ItemSortBy.DATE_CREATED),
                        sortOrder = listOf(SortOrder.DESCENDING),
                        startIndex = 0,
                        limit = itemsPerRow,
                        enableTotalRecordCount = false,
                    )
                    val items = GetItemsRequestHandler.execute(api, request).toBaseItems(api, false)
                    val successState = HomeRowLoadingState.Success("Favorite Movies", items)

                    rows.update { current ->
                        current.toMutableList().apply { set(1, successState) }
                    }
                    successState
                } catch (ex: Exception) {
                    Timber.e(ex, "Exception fetching favorite movies")
                    val errorState = HomeRowLoadingState.Error(title = "Favorite Movies", exception = ex)
                    rows.update { current ->
                        current.toMutableList().apply { set(1, errorState) }
                    }
                    errorState
                }
            }
            jobs.add(favJob)

            update(R.string.recently_released) {
                val request =
                    GetItemsRequest(
                        parentId = parentId,
                        fields = SlimItemFields,
                        includeItemTypes = listOf(BaseItemKind.MOVIE),
                        recursive = true,
                        enableUserData = true,
                        sortBy = listOf(ItemSortBy.PREMIERE_DATE),
                        sortOrder = listOf(SortOrder.DESCENDING),
                        startIndex = 0,
                        limit = itemsPerRow,
                        enableTotalRecordCount = false,
                    )
                GetItemsRequestHandler.execute(api, request).toBaseItems(api, false)
            }.also(jobs::add)

            update(R.string.recently_added) {
                val request =
                    GetItemsRequest(
                        parentId = parentId,
                        fields = SlimItemFields,
                        includeItemTypes = listOf(BaseItemKind.MOVIE),
                        recursive = true,
                        enableUserData = true,
                        sortBy = listOf(ItemSortBy.DATE_CREATED),
                        sortOrder = listOf(SortOrder.DESCENDING),
                        startIndex = 0,
                        limit = itemsPerRow,
                        enableTotalRecordCount = false,
                    )
                GetItemsRequestHandler.execute(api, request).toBaseItems(api, false)
            }.also(jobs::add)

            update(R.string.top_unwatched) {
                val request =
                    GetItemsRequest(
                        parentId = parentId,
                        fields = SlimItemFields,
                        includeItemTypes = listOf(BaseItemKind.MOVIE),
                        recursive = true,
                        enableUserData = true,
                        isPlayed = false,
                        sortBy = listOf(ItemSortBy.COMMUNITY_RATING),
                        sortOrder = listOf(SortOrder.DESCENDING),
                        startIndex = 0,
                        limit = itemsPerRow,
                        enableTotalRecordCount = false,
                    )
                GetItemsRequestHandler.execute(api, request).toBaseItems(api, false)
            }.also(jobs::add)

            val genresToAdd = listOf(
                Triple("Sci-Fi", 6, listOf("Sci-Fi", "Science Fiction")),
                Triple("Action", 7, listOf("Action")),
                Triple("Adventure", 8, listOf("Adventure")),
                Triple("Animation", 9, listOf("Animation")),
                Triple("Comedy", 10, listOf("Comedy")),
                Triple("Crime", 11, listOf("Crime")),
                Triple("Documentary", 12, listOf("Documentary")),
                Triple("Drama", 13, listOf("Drama")),
                Triple("Family", 14, listOf("Family")),
                Triple("Fantasy", 15, listOf("Fantasy")),
                Triple("History", 16, listOf("History")),
                Triple("Horror", 17, listOf("Horror")),
                Triple("Mystery", 18, listOf("Mystery")),
                Triple("Romance", 19, listOf("Romance")),
                Triple("Thriller", 20, listOf("Thriller")),
                Triple("War", 21, listOf("War")),
                Triple("Western", 22, listOf("Western"))
            )

            genresToAdd.forEach { (genreTitle, slotIndex, genreQueryList) ->
                val genreJob = viewModelScope.async(Dispatchers.IO) {
                    try {
                        val request = GetItemsRequest(
                            parentId = parentId,
                            fields = SlimItemFields,
                            includeItemTypes = listOf(BaseItemKind.MOVIE),
                            recursive = true,
                            enableUserData = true,
                            genres = genreQueryList,
                            sortBy = listOf(ItemSortBy.COMMUNITY_RATING),
                            sortOrder = listOf(SortOrder.DESCENDING),
                            startIndex = 0,
                            limit = itemsPerRow,
                            enableTotalRecordCount = false,
                        )
                        val items = GetItemsRequestHandler.execute(api, request).toBaseItems(api, false)

                        val successState = HomeRowLoadingState.Success("$genreTitle Movies", items)

                        rows.update { current ->
                            current.toMutableList().apply { set(slotIndex, successState) }
                        }
                        successState
                    } catch (ex: Exception) {
                        Timber.e(ex, "Exception fetching $genreTitle movies")
                        val errorState = HomeRowLoadingState.Error(title = "$genreTitle Movies", exception = ex)
                        rows.update { current ->
                            current.toMutableList().apply { set(slotIndex, errorState) }
                        }
                        errorState
                    }
                }
                jobs.add(genreJob)
            }

            viewModelScope.launch(Dispatchers.IO) {
                try {
                    suggestionService
                        .getSuggestionsFlow(parentId, BaseItemKind.MOVIE)
                        .collect { resource ->
                            val state =
                                when (resource) {
                                    is SuggestionsResource.Loading -> {
                                        HomeRowLoadingState.Loading(
                                            context.getString(R.string.suggestions),
                                        )
                                    }

                                    is SuggestionsResource.Success -> {
                                        HomeRowLoadingState.Success(
                                            context.getString(R.string.suggestions),
                                            resource.items,
                                        )
                                    }

                                    is SuggestionsResource.Empty -> {
                                        HomeRowLoadingState.Success(
                                            context.getString(R.string.suggestions),
                                            emptyList(),
                                        )
                                    }
                                }
                            update(R.string.suggestions, state)
                        }
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.e(ex, "Failed to fetch suggestions")
                    update(
                        R.string.suggestions,
                        HomeRowLoadingState.Error(
                            title = context.getString(R.string.suggestions),
                            exception = ex,
                        ),
                    )
                }
            }

            if (loading.value == LoadingState.Loading || loading.value == LoadingState.Pending) {
                for (i in 0..<jobs.size) {
                    val result = jobs[i].await()
                    if (result.completed) {
                        Timber.v("First success")
                        loading.setValueOnMain(LoadingState.Success)
                    }
                    break
                }
            }
        }
    }

    override fun update(
        @StringRes title: Int,
        row: HomeRowLoadingState,
    ): HomeRowLoadingState {
        rows.update { current ->
            current.toMutableList().apply { set(rowTitles[title]!!, row) }
        }
        return row
    }

    companion object {
        private val rowTitles = mapOf(
            R.string.continue_watching to 0,
            R.string.recently_released to 2,
            R.string.recently_added to 3,
            R.string.suggestions to 4,
            R.string.top_unwatched to 5
        )
    }
}

@Composable
fun RecommendedMovie(
    preferences: UserPreferences,
    parentId: UUID,
    onFocusPosition: (RowColumn) -> Unit,
    modifier: Modifier = Modifier,
    viewModel: RecommendedMovieViewModel =
        hiltViewModel<RecommendedMovieViewModel, RecommendedMovieViewModel.Factory>(
            creationCallback = { it.create(parentId) },
        ),
) {
    RecommendedContent(
        preferences = preferences,
        viewModel = viewModel,
        onFocusPosition = onFocusPosition,
        modifier = modifier,
    )
}