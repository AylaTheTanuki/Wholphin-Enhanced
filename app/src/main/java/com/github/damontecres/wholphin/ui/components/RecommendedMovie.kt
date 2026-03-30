package com.github.damontecres.wholphin.ui.components

import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.datastore.core.DataStore
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.data.ServerPreferencesDao
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.preferences.AppPreference
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.FavoriteWatchManager
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

enum class MovieSectionRowId(
    val dbId: String,
) {
    CONTINUE_WATCHING("continue_watching"),
    FAVORITES("favorites"),
    RECENTLY_RELEASED("recently_released"),
    RECENTLY_ADDED("recently_added"),
    SUGGESTIONS("suggestions"),
    TOP_UNWATCHED("top_unwatched"),
    SCI_FI("sci_fi"),
    HORROR("horror"),
    NINETIES("nineties"),
    THRILLER("thriller"),
    ANIMATION("animation"),
    MYSTERY("mystery"),
    ROMANCE("romance"),
    COMEDY("comedy"),
    SEVENTIES_EIGHTIES("seventies_eighties"),
    SIXTIES_AND_EARLIER("sixties_and_earlier"),
    ACTION("action"),
    ADVENTURE("adventure"),
    CRIME("crime"),
    DOCUMENTARY("documentary"),
    DRAMA("drama"),
    FAMILY("family"),
    FANTASY("fantasy"),
    HISTORY("history"),
    WAR("war"),
    WESTERN("western");

    fun title(context: Context): String =
        when (this) {
            CONTINUE_WATCHING -> context.getString(R.string.continue_watching)
            FAVORITES -> "Favorite Movies"
            RECENTLY_RELEASED -> context.getString(R.string.recently_released)
            RECENTLY_ADDED -> context.getString(R.string.recently_added)
            SUGGESTIONS -> context.getString(R.string.suggestions)
            TOP_UNWATCHED -> context.getString(R.string.top_unwatched)
            SCI_FI -> "Sci-Fi Movies"
            HORROR -> "Horror Movies"
            NINETIES -> context.getString(R.string.nineties_movies)
            THRILLER -> "Thriller Movies"
            ANIMATION -> "Animation Movies"
            MYSTERY -> "Mystery Movies"
            ROMANCE -> "Romance Movies"
            COMEDY -> "Comedy Movies"
            SEVENTIES_EIGHTIES -> context.getString(R.string.seventies_eighties_movies)
            SIXTIES_AND_EARLIER -> context.getString(R.string.sixties_and_earlier_movies)
            ACTION -> "Action Movies"
            ADVENTURE -> "Adventure Movies"
            CRIME -> "Crime Movies"
            DOCUMENTARY -> "Documentary Movies"
            DRAMA -> "Drama Movies"
            FAMILY -> "Family Movies"
            FANTASY -> "Fantasy Movies"
            HISTORY -> "History Movies"
            WAR -> "War Movies"
            WESTERN -> "Western Movies"
        }

    companion object {
        val defaultOrder =
            listOf(
                CONTINUE_WATCHING,
                FAVORITES,
                RECENTLY_RELEASED,
                RECENTLY_ADDED,
                SUGGESTIONS,
                TOP_UNWATCHED,
                SCI_FI,
                HORROR,
                NINETIES,
                THRILLER,
                ANIMATION,
                MYSTERY,
                ROMANCE,
                COMEDY,
                SEVENTIES_EIGHTIES,
                SIXTIES_AND_EARLIER,
                ACTION,
                ADVENTURE,
                CRIME,
                DOCUMENTARY,
                DRAMA,
                FAMILY,
                FANTASY,
                HISTORY,
                WAR,
                WESTERN,
            )

        fun normalize(savedOrder: List<String>): List<MovieSectionRowId> {
            val saved =
                savedOrder.mapNotNull { savedId ->
                    entries.firstOrNull { it.dbId == savedId }
                }
            return saved + defaultOrder.filterNot(saved::contains)
        }
    }
}

private fun List<BaseItem>.unwatchedOnly(): List<BaseItem> = filterNot { it.played }

@HiltViewModel(assistedFactory = RecommendedMovieViewModel.Factory::class)
class RecommendedMovieViewModel
@AssistedInject
constructor(
    @ApplicationContext context: Context,
    api: ApiClient,
    serverRepository: ServerRepository,
    private val serverPreferencesDao: ServerPreferencesDao,
    private val preferencesDataStore: DataStore<AppPreferences>,
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
        fun create(parentId: UUID): RecommendedMovieViewModel
    }

    private val cinematicWideOptions = HomeRowViewOptions(
        heightDp = Cards.HEIGHT_EPISODE,
        aspectRatio = AspectRatio.WIDE,
        imageType = ViewOptionImageType.THUMB,
        contentScale = PrefContentScale.CROP
    )

    private var orderedRows = MovieSectionRowId.defaultOrder

    override val rows =
        MutableStateFlow<List<HomeRowLoadingState>>(
            orderedRows.map { HomeRowLoadingState.Pending(it.title(context)) }
        )

    override fun favoritesRowIndices(): List<Int> =
        listOfNotNull(indexOf(MovieSectionRowId.FAVORITES).takeIf { it >= 0 })

    private fun indexOf(rowId: MovieSectionRowId): Int = orderedRows.indexOf(rowId)

    private fun setRow(
        rowId: MovieSectionRowId,
        state: HomeRowLoadingState,
    ) {
        val index = indexOf(rowId)
        if (index < 0) return
        rows.update { current ->
            current.toMutableList().apply { set(index, state) }
        }
    }

    // FIX: Re-fetches only Continue Watching (row 0) and updates it in place.
    // All other rows are left untouched so they don't reload or flicker.
    // This is called every ON_RESUME from RecommendedContent.
    override fun refreshWatchingRows() {
        viewModelScope.launch(Dispatchers.IO + ExceptionHandler()) {
            try {
                val itemsPerRow = preferencesDataStore.data.firstOrNull()
                    ?.homePagePreferences
                    ?.maxItemsPerRow
                    ?: AppPreference.HomePageItems.defaultValue.toInt()

                val request = GetResumeItemsRequest(
                    parentId = parentId,
                    fields = SlimItemFields,
                    includeItemTypes = listOf(BaseItemKind.MOVIE),
                    enableUserData = true,
                    startIndex = 0,
                    limit = itemsPerRow,
                    enableTotalRecordCount = false,
                )
                val resumeItems = GetResumeItemsRequestHandler
                    .execute(api, request)
                    .toBaseItems(api, false)

                setRow(
                    MovieSectionRowId.CONTINUE_WATCHING,
                    HomeRowLoadingState.Success(
                        context.getString(R.string.continue_watching),
                        resumeItems,
                        viewOptions = cinematicWideOptions,
                    ),
                )
            } catch (ex: Exception) {
                Timber.e(ex, "Exception refreshing movie watching rows")
            }
        }
    }

    override fun reloadFavoritesRows() {
        viewModelScope.launch(Dispatchers.IO + ExceptionHandler()) {
            try {
                val request = GetItemsRequest(
                    parentId = parentId,
                    fields = SlimItemFields,
                    includeItemTypes = listOf(BaseItemKind.MOVIE),
                    recursive = true,
                    enableUserData = true,
                    isFavorite = true,
                    sortBy = listOf(ItemSortBy.RANDOM),
                    enableTotalRecordCount = false,
                )
                val items = GetItemsRequestHandler.execute(api, request).toBaseItems(api, false)
                    .distinctBy { it.id }
                setRow(
                    MovieSectionRowId.FAVORITES,
                    HomeRowLoadingState.Success(MovieSectionRowId.FAVORITES.title(context), items),
                )
            } catch (ex: Exception) {
                Timber.e(ex, "Exception reloading favorite movies row")
            }
        }
    }

    override fun refreshRecentRows() {
        viewModelScope.launch(Dispatchers.IO + ExceptionHandler()) {
            try {
                val itemsPerRow = preferencesDataStore.data.firstOrNull()
                    ?.homePagePreferences
                    ?.maxItemsPerRow
                    ?: AppPreference.HomePageItems.defaultValue.toInt()

                val request = GetItemsRequest(
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
                val items =
                    mergeWithExistingWatchedItems(
                        MovieSectionRowId.RECENTLY_ADDED,
                        GetItemsRequestHandler.execute(api, request).toBaseItems(api, false).unwatchedOnly(),
                    )
                setRow(
                    MovieSectionRowId.RECENTLY_ADDED,
                    HomeRowLoadingState.Success(context.getString(R.string.recently_added), items),
                )
            } catch (ex: Exception) {
                Timber.e(ex, "Exception refreshing recently added movies row")
            }
        }
    }

    private fun mergeWithExistingWatchedItems(
        rowId: MovieSectionRowId,
        freshItems: List<BaseItem>,
    ): List<BaseItem> {
        val existingItems =
            (rows.value.getOrNull(indexOf(rowId)) as? HomeRowLoadingState.Success)
                ?.items
                ?.filterIsInstance<BaseItem>()
                .orEmpty()
        if (existingItems.isEmpty()) {
            return freshItems.distinctBy { it.id }
        }

        val freshById = freshItems.associateBy { it.id }.toMutableMap()
        val merged =
            existingItems.mapNotNull { existingItem ->
                freshById.remove(existingItem.id) ?: existingItem.takeIf { it.played }
            } + freshById.values

        return merged.distinctBy { it.id }
    }

    private suspend fun fetchRandomRow(
        rowId: MovieSectionRowId,
        genres: List<String>? = null,
        years: List<Int>? = null,
        itemsPerRow: Int,
    ): HomeRowLoadingState {
        val request =
            GetItemsRequest(
                parentId = parentId,
                fields = SlimItemFields,
                includeItemTypes = listOf(BaseItemKind.MOVIE),
                recursive = true,
                enableUserData = true,
                isPlayed = false,
                genres = genres,
                years = years,
                sortBy = listOf(ItemSortBy.RANDOM),
                startIndex = 0,
                limit = itemsPerRow,
                enableTotalRecordCount = false,
            )
        val items = GetItemsRequestHandler.execute(api, request).toBaseItems(api, false).unwatchedOnly()
        return HomeRowLoadingState.Success(rowId.title(context), items)
    }

    override fun init() {
        viewModelScope.launch(Dispatchers.IO + ExceptionHandler()) {
            serverRepository.currentUser.value?.let { user ->
                orderedRows =
                    MovieSectionRowId.normalize(
                        serverPreferencesDao
                            .getMovieSectionRowPreferences(user.rowId)
                            .map { it.rowId },
                    )
                rows.value = orderedRows.map { HomeRowLoadingState.Pending(it.title(context)) }
            }

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
                setRow(
                    MovieSectionRowId.CONTINUE_WATCHING,
                    HomeRowLoadingState.Success(
                        context.getString(R.string.continue_watching),
                        resumeItems,
                        viewOptions = cinematicWideOptions,
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
                        sortBy = listOf(ItemSortBy.RANDOM),
                        enableTotalRecordCount = false,
                    )
                    val items = GetItemsRequestHandler.execute(api, request).toBaseItems(api, false)
                        .distinctBy { it.id }
                    val successState = HomeRowLoadingState.Success(MovieSectionRowId.FAVORITES.title(context), items)
                    setRow(MovieSectionRowId.FAVORITES, successState)
                    successState
                } catch (ex: Exception) {
                    Timber.e(ex, "Exception fetching favorite movies")
                    val errorState = HomeRowLoadingState.Error(title = MovieSectionRowId.FAVORITES.title(context), exception = ex)
                    setRow(MovieSectionRowId.FAVORITES, errorState)
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
                        isPlayed = false,
                        sortBy = listOf(ItemSortBy.PREMIERE_DATE),
                        sortOrder = listOf(SortOrder.DESCENDING),
                        startIndex = 0,
                        limit = itemsPerRow,
                        enableTotalRecordCount = false,
                    )
                GetItemsRequestHandler.execute(api, request).toBaseItems(api, false).unwatchedOnly()
            }.also(jobs::add)

            update(R.string.recently_added) {
                val request =
                    GetItemsRequest(
                        parentId = parentId,
                        fields = SlimItemFields,
                        includeItemTypes = listOf(BaseItemKind.MOVIE),
                        recursive = true,
                        enableUserData = true,
                        isPlayed = false,
                        sortBy = listOf(ItemSortBy.DATE_CREATED),
                        sortOrder = listOf(SortOrder.DESCENDING),
                        startIndex = 0,
                        limit = itemsPerRow,
                        enableTotalRecordCount = false,
                    )
                GetItemsRequestHandler.execute(api, request).toBaseItems(api, false).unwatchedOnly()
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

            val randomRows = listOf(
                Triple(MovieSectionRowId.SCI_FI, listOf("Sci-Fi", "Science Fiction"), null),
                Triple(MovieSectionRowId.HORROR, listOf("Horror"), null),
                Triple(MovieSectionRowId.NINETIES, null, (1990..1999).toList()),
                Triple(MovieSectionRowId.THRILLER, listOf("Thriller"), null),
                Triple(MovieSectionRowId.ANIMATION, listOf("Animation"), null),
                Triple(MovieSectionRowId.MYSTERY, listOf("Mystery"), null),
                Triple(MovieSectionRowId.ROMANCE, listOf("Romance"), null),
                Triple(MovieSectionRowId.COMEDY, listOf("Comedy"), null),
                Triple(MovieSectionRowId.SEVENTIES_EIGHTIES, null, (1970..1989).toList()),
                Triple(MovieSectionRowId.SIXTIES_AND_EARLIER, null, (1900..1969).toList()),
                Triple(MovieSectionRowId.ACTION, listOf("Action"), null),
                Triple(MovieSectionRowId.ADVENTURE, listOf("Adventure"), null),
                Triple(MovieSectionRowId.CRIME, listOf("Crime"), null),
                Triple(MovieSectionRowId.DOCUMENTARY, listOf("Documentary"), null),
                Triple(MovieSectionRowId.DRAMA, listOf("Drama"), null),
                Triple(MovieSectionRowId.FAMILY, listOf("Family"), null),
                Triple(MovieSectionRowId.FANTASY, listOf("Fantasy"), null),
                Triple(MovieSectionRowId.HISTORY, listOf("History"), null),
                Triple(MovieSectionRowId.WAR, listOf("War"), null),
                Triple(MovieSectionRowId.WESTERN, listOf("Western"), null),
            )

            randomRows.forEach { (rowId, genres, years) ->
                val rowJob = viewModelScope.async(Dispatchers.IO) {
                    try {
                        val successState = fetchRandomRow(rowId, genres, years, itemsPerRow)
                        setRow(rowId, successState)
                        successState
                    } catch (ex: Exception) {
                        Timber.e(ex, "Exception fetching %s row", rowId.dbId)
                        val errorState = HomeRowLoadingState.Error(title = rowId.title(context), exception = ex)
                        setRow(rowId, errorState)
                        errorState
                    }
                }
                jobs.add(rowJob)
            }

            viewModelScope.launch(Dispatchers.IO) {
                try {
                    suggestionService
                        .getSuggestionsFlow(parentId, BaseItemKind.MOVIE)
                        .collect { resource ->
                            val state =
                                when (resource) {
                                    is SuggestionsResource.Loading -> HomeRowLoadingState.Loading(context.getString(R.string.suggestions))
                                    is SuggestionsResource.Success -> HomeRowLoadingState.Success(context.getString(R.string.suggestions), resource.items.unwatchedOnly())
                                    is SuggestionsResource.Empty -> HomeRowLoadingState.Success(context.getString(R.string.suggestions), emptyList())
                                }
                            setRow(MovieSectionRowId.SUGGESTIONS, state)
                        }
                } catch (ex: CancellationException) {
                    throw ex
                } catch (ex: Exception) {
                    Timber.e(ex, "Failed to fetch suggestions")
                    setRow(
                        MovieSectionRowId.SUGGESTIONS,
                        HomeRowLoadingState.Error(title = context.getString(R.string.suggestions), exception = ex),
                    )
                }
            }

            if (loading.value == LoadingState.Loading || loading.value == LoadingState.Pending) {
                for (i in 0..<jobs.size) {
                    val result = jobs[i].await()
                    if (result is HomeRowLoadingState.Success) {
                        Timber.v("First success")
                        loading.setValueOnMain(LoadingState.Success)
                        break
                    }
                }
            }
        }
    }

    override fun update(@StringRes title: Int, row: HomeRowLoadingState): HomeRowLoadingState {
        val rowId =
            when (title) {
                R.string.continue_watching -> MovieSectionRowId.CONTINUE_WATCHING
                R.string.recently_released -> MovieSectionRowId.RECENTLY_RELEASED
                R.string.recently_added -> MovieSectionRowId.RECENTLY_ADDED
                R.string.suggestions -> MovieSectionRowId.SUGGESTIONS
                R.string.top_unwatched -> MovieSectionRowId.TOP_UNWATCHED
                else -> return row
            }
        setRow(rowId, row)
        return row
    }

    companion object {
        fun orderedRowsForSettings(savedOrder: List<String>): List<MovieSectionRowId> =
            MovieSectionRowId.normalize(savedOrder)
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
