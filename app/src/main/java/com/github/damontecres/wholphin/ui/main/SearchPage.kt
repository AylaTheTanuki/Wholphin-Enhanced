package com.github.damontecres.wholphin.ui.main

import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.asFlow
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.SearchHistoryDao
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.data.model.SearchHistoryEntry
import com.github.damontecres.wholphin.data.model.SeerrItemType
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.SeerrService
import com.github.damontecres.wholphin.ui.Cards
import com.github.damontecres.wholphin.ui.SlimItemFields
import com.github.damontecres.wholphin.ui.cards.DiscoverItemCard
import com.github.damontecres.wholphin.ui.cards.EpisodeCard
import com.github.damontecres.wholphin.ui.cards.ItemRow
import com.github.damontecres.wholphin.ui.cards.SeasonCard
import com.github.damontecres.wholphin.ui.components.SearchEditTextBox
import com.github.damontecres.wholphin.ui.components.VoiceInputManager
import com.github.damontecres.wholphin.ui.components.VoiceSearchButton
import com.github.damontecres.wholphin.ui.data.RowColumn
import com.github.damontecres.wholphin.ui.isNotNullOrBlank
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.onMain
import com.github.damontecres.wholphin.ui.rememberPosition
import com.github.damontecres.wholphin.ui.setValueOnMain
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.util.ApiRequestPager
import com.github.damontecres.wholphin.util.ExceptionHandler
import com.github.damontecres.wholphin.util.GetItemsRequestHandler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import timber.log.Timber
import javax.inject.Inject

@HiltViewModel
class SearchViewModel
    @Inject
    constructor(
        val api: ApiClient,
        val navigationManager: NavigationManager,
        private val seerrService: SeerrService,
        val voiceInputManager: VoiceInputManager,
        private val searchHistoryDao: SearchHistoryDao,
        private val serverRepository: ServerRepository,
    ) : ViewModel() {
        val voiceState = voiceInputManager.state
        val soundLevel = voiceInputManager.soundLevel
        val partialResult = voiceInputManager.partialResult

        val movies = MutableLiveData<SearchResult>(SearchResult.NoQuery)
        val series = MutableLiveData<SearchResult>(SearchResult.NoQuery)
        val episodes = MutableLiveData<SearchResult>(SearchResult.NoQuery)
        val collections = MutableLiveData<SearchResult>(SearchResult.NoQuery)
        val seerrResults = MutableLiveData<SearchResult>(SearchResult.NoQuery)
        private val _recentSearches = MutableStateFlow<List<String>>(emptyList())
        val recentSearches: StateFlow<List<String>> = _recentSearches

        private var currentQuery: String? = null

        fun search(query: String?) {
            if (currentQuery == query) {
                return
            }
            currentQuery = query
            if (query.isNotNullOrBlank()) {
                movies.value = SearchResult.Searching
                series.value = SearchResult.Searching
                episodes.value = SearchResult.Searching
                collections.value = SearchResult.Searching
                searchInternal(query, BaseItemKind.MOVIE, movies)
                searchInternal(query, BaseItemKind.SERIES, series)
                searchInternal(query, BaseItemKind.EPISODE, episodes)
                searchInternal(query, BaseItemKind.BOX_SET, collections)
                searchSeerr(query)
            } else {
                movies.value = SearchResult.NoQuery
                series.value = SearchResult.NoQuery
                episodes.value = SearchResult.NoQuery
                collections.value = SearchResult.NoQuery
                seerrResults.value = SearchResult.NoQuery
            }
        }

        private fun searchInternal(
            query: String,
            type: BaseItemKind,
            target: MutableLiveData<SearchResult>,
        ) {
            viewModelScope.launch(ExceptionHandler() + Dispatchers.IO) {
                try {
                    val request =
                        GetItemsRequest(
                            searchTerm = query,
                            recursive = true,
                            includeItemTypes = listOf(type),
                            fields = SlimItemFields,
                            limit = 25,
                        )
                    val pager =
                        ApiRequestPager(api, request, GetItemsRequestHandler, viewModelScope)
                    pager.init()
                    withContext(Dispatchers.Main) {
                        target.value = SearchResult.Success(pager)
                    }
                } catch (ex: Exception) {
                    Timber.e(ex, "Exception searching for $type")
                    withContext(Dispatchers.Main) {
                        target.value = SearchResult.Error(ex)
                    }
                }
            }
        }

        private fun searchSeerr(query: String) {
            viewModelScope.launchIO {
                if (seerrService.active.first()) {
                    seerrResults.setValueOnMain(SearchResult.Searching)
                    val results =
                        seerrService
                            .search(query)
                            .map { DiscoverItem(it) }
                            .filter { it.type == SeerrItemType.MOVIE || it.type == SeerrItemType.TV }
                    seerrResults.setValueOnMain(SearchResult.SuccessSeerr(results))
                }
            }
        }

        init {
            addCloseable(voiceInputManager)
            viewModelScope.launchIO {
                serverRepository.currentUser.asFlow().collectLatest { user ->
                    if (user == null) {
                        _recentSearches.value = emptyList()
                    } else {
                        searchHistoryDao.observeRecentSearches(user.rowId).collect { entries ->
                            _recentSearches.value = entries.map(SearchHistoryEntry::query)
                        }
                    }
                }
            }
        }

        fun getHints(query: String) {
            // TODO
//        api.searchApi.getSearchHints()
        }

        fun saveRecentSearch(query: String) {
            val normalized = query.trim()
            if (normalized.isBlank()) return

            viewModelScope.launchIO {
                val userId = serverRepository.currentUser.value?.rowId ?: return@launchIO
                searchHistoryDao.upsert(
                    SearchHistoryEntry(
                        userId = userId,
                        query = normalized,
                        updatedAt = System.currentTimeMillis(),
                    ),
                )
                searchHistoryDao.trimRecentSearches(userId)
            }
        }
    }

sealed interface SearchResult {
    data object NoQuery : SearchResult

    data object Searching : SearchResult

    data class Error(
        val ex: Exception,
    ) : SearchResult

    data class Success(
        val items: List<BaseItem?>,
    ) : SearchResult

    data class SuccessSeerr(
        val items: List<DiscoverItem>,
    ) : SearchResult
}

private const val SEARCH_ROW = 0
private const val MOVIE_ROW = SEARCH_ROW + 1
private const val COLLECTION_ROW = MOVIE_ROW + 1
private const val SERIES_ROW = COLLECTION_ROW + 1
private const val EPISODE_ROW = SERIES_ROW + 1
private const val SEERR_ROW = EPISODE_ROW + 1

/** Delay for focus to settle after voice search dialog dismisses. */
private const val VOICE_RESULT_FOCUS_DELAY_MS = 350L

@Composable
fun SearchPage(
    userPreferences: UserPreferences,
    modifier: Modifier = Modifier,
    viewModel: SearchViewModel = hiltViewModel(),
) {
    val context = LocalContext.current
    val focusManager = LocalFocusManager.current
    val keyboardController = LocalSoftwareKeyboardController.current
    val movies by viewModel.movies.observeAsState(SearchResult.NoQuery)
    val collections by viewModel.collections.observeAsState(SearchResult.NoQuery)
    val series by viewModel.series.observeAsState(SearchResult.NoQuery)
    val episodes by viewModel.episodes.observeAsState(SearchResult.NoQuery)
    val seerrResults by viewModel.seerrResults.observeAsState(SearchResult.NoQuery)
    val recentSearches by viewModel.recentSearches.collectAsState()

//    val query = rememberTextFieldState()
    var query by rememberSaveable { mutableStateOf("") }
    val focusRequesters = remember { List(SEERR_ROW + 1) { FocusRequester() } }

    var position by rememberPosition(0, 0)
    var searchClicked by rememberSaveable { mutableStateOf(false) }
    var immediateSearchQuery by rememberSaveable { mutableStateOf<String?>(null) }
    val fallbackFocusRequester =
        focusRequesters.getOrNull(position.row)?.takeIf { position.row >= 0 } ?: focusRequesters[SEARCH_ROW]

    LifecycleResumeEffect(Unit) {
        onPauseOrDispose {
            viewModel.voiceInputManager.stopListening()
            keyboardController?.hide()
            focusManager.clearFocus(force = true)
        }
    }

    fun triggerImmediateSearch(searchQuery: String) {
        val normalizedQuery = searchQuery.trim()
        immediateSearchQuery = normalizedQuery
        searchClicked = true
        viewModel.saveRecentSearch(normalizedQuery)
        viewModel.search(normalizedQuery)
    }

    LaunchedEffect(query) {
        when {
            immediateSearchQuery == query -> {
                immediateSearchQuery = null
            }

            else -> {
                delay(750L)
                viewModel.search(query)
            }
        }
    }
    LaunchedEffect(Unit) {
        focusRequesters.getOrNull(position.row)?.tryRequestFocus()
    }
    val onClickItem = { index: Int, item: BaseItem ->
        keyboardController?.hide()
        focusManager.clearFocus(force = true)
        viewModel.navigationManager.navigateTo(item.destination())
    }

    LaunchedEffect(searchClicked, movies, collections, series, episodes, seerrResults) {
        if (!searchClicked) return@LaunchedEffect

        withContext(Dispatchers.IO) {
            // Want to focus on the first successful row after all of the ones before it are finished searching
            val results = listOf(movies, collections, series, episodes, seerrResults)
            val firstSuccess =
                results.indexOfFirst { it is SearchResult.Success || it is SearchResult.SuccessSeerr }
            if (firstSuccess >= 0) {
                val anyBeforeSearching =
                    results.subList(0, firstSuccess).any { it is SearchResult.Searching }
                if (!anyBeforeSearching) {
                    // 0-th row is the search bar
                    position = RowColumn(firstSuccess + 1, 0)
                    onMain { focusRequesters[firstSuccess + 1].tryRequestFocus() }
                }
            }
        }
    }

    LazyColumn(
        contentPadding = PaddingValues(start = 16.dp, end = 16.dp, top = 16.dp, bottom = 44.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier =
            modifier
                .focusGroup()
                .focusProperties {
                    onEnter = {
                        fallbackFocusRequester.tryRequestFocus()
                    }
                },
    ) {
        item {
            Box(
                contentAlignment = Alignment.Center,
                modifier = Modifier.fillMaxWidth(),
            ) {
                var isSearchActive by remember { mutableStateOf(false) }
                var isTextFieldFocused by remember { mutableStateOf(false) }
                var focusedRecentSearchIndex by remember { mutableIntStateOf(-1) }
                val textFieldFocusRequester = remember { FocusRequester() }
                val searchActivationScope = rememberCoroutineScope()
                val recentSearchFocusRequesters =
                    remember(recentSearches.size) {
                        List(recentSearches.size) { FocusRequester() }
                    }
                val showRecentSearches =
                    recentSearches.isNotEmpty() &&
                        !isSearchActive &&
                        (isTextFieldFocused || focusedRecentSearchIndex >= 0)

                fun activateSearch() {
                    isSearchActive = true
                    focusedRecentSearchIndex = -1
                    searchActivationScope.launch {
                        textFieldFocusRequester.tryRequestFocus()
                        withFrameNanos { }
                        textFieldFocusRequester.tryRequestFocus()
                        keyboardController?.show()
                    }
                }

                BackHandler(isTextFieldFocused || focusedRecentSearchIndex >= 0) {
                    when {
                        isSearchActive -> {
                            isSearchActive = false
                            keyboardController?.hide()
                        }

                        else -> {
                            focusedRecentSearchIndex = -1
                            focusManager.moveFocus(FocusDirection.Next)
                        }
                    }
                }

                Row(
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalAlignment = Alignment.Top,
                    modifier =
                        Modifier
                            .focusGroup()
                            .focusRestorer(
                                recentSearchFocusRequesters.getOrNull(
                                    focusedRecentSearchIndex.coerceAtLeast(0),
                                ) ?: textFieldFocusRequester,
                            )
                            .focusRequester(focusRequesters[SEARCH_ROW]),
                ) {
                    VoiceSearchButton(
                        onSpeechResult = { spokenText ->
                            query = spokenText
                            triggerImmediateSearch(spokenText)
                        },
                        voiceInputManager = viewModel.voiceInputManager,
                    )

                    Column(
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        modifier =
                            Modifier
                                .widthIn(min = 280.dp, max = 520.dp)
                                .fillMaxWidth(),
                    ) {
                        SearchEditTextBox(
                            value = query,
                            onValueChange = {
                                isSearchActive = true
                                focusedRecentSearchIndex = -1
                                query = it
                            },
                            onSearchClick = { triggerImmediateSearch(query) },
                            readOnly = !isSearchActive,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .focusRequester(textFieldFocusRequester)
                                    .onFocusChanged { state ->
                                        isTextFieldFocused = state.isFocused
                                        if (!state.isFocused && focusedRecentSearchIndex < 0) {
                                            isSearchActive = false
                                        }
                                    }.onPreviewKeyEvent { event ->
                                        val isActivationKey =
                                            event.key in listOf(Key.DirectionCenter, Key.Enter)
                                        when {
                                            event.type == KeyEventType.KeyUp && isActivationKey && !isSearchActive -> {
                                                activateSearch()
                                                true
                                            }

                                            event.type == KeyEventType.KeyUp &&
                                                event.key == Key.DirectionDown &&
                                                showRecentSearches -> {
                                                focusedRecentSearchIndex = 0
                                                recentSearchFocusRequesters.firstOrNull()?.tryRequestFocus()
                                                true
                                            }

                                            else -> false
                                        }
                                    },
                        )

                        AnimatedVisibility(showRecentSearches) {
                            Column(
                                modifier =
                                    Modifier
                                        .fillMaxWidth(),
                                verticalArrangement = Arrangement.spacedBy(4.dp),
                            ) {
                                recentSearches.take(2).forEachIndexed { index, recentQuery ->
                                    ListItem(
                                        selected = false,
                                        onClick = {
                                            focusedRecentSearchIndex = -1
                                            query = recentQuery
                                            triggerImmediateSearch(recentQuery)
                                        },
                                        headlineContent = {
                                            Text(recentQuery)
                                        },
                                        supportingContent = {
                                            Text(stringResource(R.string.search))
                                        },
                                        modifier =
                                            Modifier
                                                .fillMaxWidth()
                                                .focusRequester(recentSearchFocusRequesters[index])
                                                .onFocusChanged { state ->
                                                    if (state.isFocused) {
                                                        focusedRecentSearchIndex = index
                                                        isSearchActive = false
                                                    } else if (focusedRecentSearchIndex == index) {
                                                        focusedRecentSearchIndex = -1
                                                    }
                                                }.onPreviewKeyEvent { event ->
                                                    if (
                                                        index == 0 &&
                                                        event.type == KeyEventType.KeyUp &&
                                                        event.key == Key.DirectionUp
                                                    ) {
                                                        textFieldFocusRequester.tryRequestFocus()
                                                        true
                                                    } else {
                                                        false
                                                    }
                                                },
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
        searchResultRow(
            title = context.getString(R.string.movies),
            result = movies,
            rowIndex = MOVIE_ROW,
            position = position,
            focusRequester = focusRequesters[MOVIE_ROW],
            onClickItem = onClickItem,
            onClickPosition = { position = it },
            modifier = Modifier.fillMaxWidth(),
        )
        searchResultRow(
            title = context.getString(R.string.collections),
            result = collections,
            rowIndex = COLLECTION_ROW,
            position = position,
            focusRequester = focusRequesters[COLLECTION_ROW],
            onClickItem = onClickItem,
            onClickPosition = { position = it },
            modifier = Modifier.fillMaxWidth(),
        )
        searchResultRow(
            title = context.getString(R.string.tv_shows),
            result = series,
            rowIndex = SERIES_ROW,
            position = position,
            focusRequester = focusRequesters[SERIES_ROW],
            onClickItem = onClickItem,
            onClickPosition = { position = it },
            modifier = Modifier.fillMaxWidth(),
        )
        searchResultRow(
            title = context.getString(R.string.episodes),
            result = episodes,
            rowIndex = EPISODE_ROW,
            position = position,
            focusRequester = focusRequesters[EPISODE_ROW],
            onClickItem = onClickItem,
            onClickPosition = { position = it },
            modifier = Modifier.fillMaxWidth(),
            cardContent = @Composable { index, item, mod, onClick, onLongClick ->
                EpisodeCard(
                    item = item,
                    onClick = {
                        position = RowColumn(EPISODE_ROW, index)
                        onClick.invoke()
                    },
                    onLongClick = onLongClick,
                    imageHeight = 140.dp,
                    modifier = mod.padding(horizontal = 8.dp),
                )
            },
        )
        searchResultRow(
            title = context.getString(R.string.discover),
            result = seerrResults,
            rowIndex = SEERR_ROW,
            position = position,
            focusRequester = focusRequesters[SEERR_ROW],
            onClickItem = { _, _ ->
                // no-op
            },
            onClickDiscover = { _, item ->
                val dest =
                    if (item.jellyfinItemId != null && item.type.baseItemKind != null) {
                        Destination.MediaItem(
                            itemId = item.jellyfinItemId,
                            type = item.type.baseItemKind,
                        )
                    } else {
                        Destination.DiscoveredItem(item)
                    }
                keyboardController?.hide()
                focusManager.clearFocus(force = true)
                viewModel.navigationManager.navigateTo(dest)
            },
            onClickPosition = { position = it },
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

fun LazyListScope.searchResultRow(
    title: String,
    result: SearchResult,
    rowIndex: Int,
    position: RowColumn,
    focusRequester: FocusRequester,
    onClickItem: (Int, BaseItem) -> Unit,
    onClickPosition: (RowColumn) -> Unit,
    modifier: Modifier = Modifier,
    onClickDiscover: ((Int, DiscoverItem) -> Unit)? = null,
    cardContent: @Composable (
        index: Int,
        item: BaseItem?,
        modifier: Modifier,
        onClick: () -> Unit,
        onLongClick: () -> Unit,
    ) -> Unit = @Composable { index, item, mod, onClick, onLongClick ->
        SeasonCard(
            item = item,
            onClick = {
                onClickPosition.invoke(RowColumn(rowIndex, index))
                onClick.invoke()
            },
            onLongClick = onLongClick,
            imageHeight = Cards.height2x3,
            modifier = mod,
        )
    },
) {
    item {
        when (val r = result) {
            is SearchResult.Error -> {
                SearchResultPlaceholder(
                    title = title,
                    message = r.ex.localizedMessage ?: "Error occurred during search",
                    messageColor = MaterialTheme.colorScheme.error,
                    modifier = Modifier,
                )
            }

            SearchResult.NoQuery -> {
                // no-op
            }

            SearchResult.Searching -> {
                SearchResultPlaceholder(
                    title = title,
                    message = stringResource(R.string.searching),
                    modifier = modifier,
                )
            }

            is SearchResult.Success -> {
                if (r.items.isEmpty()) {
                    SearchResultPlaceholder(
                        title = title,
                        message = stringResource(R.string.no_results),
                        modifier = modifier,
                    )
                } else {
                    ItemRow(
                        title = title,
                        items = r.items,
                        onClickItem = onClickItem,
                        onLongClickItem = { _, _ -> },
                        modifier = modifier.focusRequester(focusRequester),
                        cardContent = cardContent,
                    )
                }
            }

            is SearchResult.SuccessSeerr -> {
                if (r.items.isEmpty()) {
                    SearchResultPlaceholder(
                        title = title,
                        message = stringResource(R.string.no_results),
                        modifier = modifier,
                    )
                } else {
                    ItemRow(
                        title = title,
                        items = r.items,
                        onClickItem = { index, item ->
                            onClickPosition.invoke(RowColumn(rowIndex, index))
                            onClickDiscover?.invoke(index, item)
                        },
                        onLongClickItem = { _, _ -> },
                        modifier = modifier.focusRequester(focusRequester),
                        cardContent = { index: Int, item: DiscoverItem?, mod: Modifier, onClick: () -> Unit, onLongClick: () -> Unit ->
                            DiscoverItemCard(
                                item = item,
                                onClick = onClick,
                                onLongClick = onLongClick,
                                showOverlay = true,
                                modifier = mod,
                            )
                        },
                    )
                }
            }
        }
    }
}

@Composable
fun SearchResultPlaceholder(
    title: String,
    message: String,
    modifier: Modifier = Modifier,
    messageColor: Color = MaterialTheme.colorScheme.onBackground,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier = modifier.padding(bottom = 32.dp),
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
        )
        Text(
            text = message,
            style = MaterialTheme.typography.titleMedium,
            color = messageColor,
        )
    }
}
