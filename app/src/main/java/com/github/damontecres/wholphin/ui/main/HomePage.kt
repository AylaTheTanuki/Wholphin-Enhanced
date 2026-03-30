package com.github.damontecres.wholphin.ui.main

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.gestures.LocalBringIntoViewSpec
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.focusRestorer
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.ui.Cards
import com.github.damontecres.wholphin.ui.cards.BannerCard
import com.github.damontecres.wholphin.ui.cards.BannerCardWithTitle
import com.github.damontecres.wholphin.ui.cards.GenreCard
import com.github.damontecres.wholphin.ui.cards.ItemRow
import com.github.damontecres.wholphin.ui.cards.PreloadHomeRowImages
import com.github.damontecres.wholphin.ui.components.CircularProgress
import com.github.damontecres.wholphin.ui.components.DialogParams
import com.github.damontecres.wholphin.ui.components.DialogPopup
import com.github.damontecres.wholphin.ui.components.EpisodeName
import com.github.damontecres.wholphin.ui.components.ErrorMessage
import com.github.damontecres.wholphin.ui.components.FocusableItemRow
import com.github.damontecres.wholphin.ui.components.LoadingPage
import com.github.damontecres.wholphin.ui.components.QuickDetails
import com.github.damontecres.wholphin.ui.data.AddPlaylistViewModel
import com.github.damontecres.wholphin.ui.data.RowColumn
import com.github.damontecres.wholphin.ui.detail.MoreDialogActions
import com.github.damontecres.wholphin.ui.detail.PlaylistDialog
import com.github.damontecres.wholphin.ui.detail.PlaylistLoadingState
import com.github.damontecres.wholphin.ui.detail.buildMoreDialogItemsForHome
import com.github.damontecres.wholphin.ui.indexOfFirstOrNull
import com.github.damontecres.wholphin.ui.isNotNullOrBlank
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.playback.isPlayKeyUp
import com.github.damontecres.wholphin.ui.playback.playable
import com.github.damontecres.wholphin.ui.playback.scale
import com.github.damontecres.wholphin.ui.rememberPosition
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.ui.util.ScrollToTopBringIntoViewSpec
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import com.github.damontecres.wholphin.util.LoadingState
import kotlinx.coroutines.delay
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.MediaType
import timber.log.Timber
import java.util.UUID
import kotlin.time.Duration

private const val BACKDROP_FOCUS_SETTLE_DELAY_MS = 150L

@Composable
fun HomePage(
    preferences: UserPreferences,
    modifier: Modifier = Modifier,
    viewModel: HomeViewModel = hiltViewModel(),
    playlistViewModel: AddPlaylistViewModel = hiltViewModel(),
) {
    val lifecycleOwner = LocalLifecycleOwner.current

    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                if (!viewModel.hasInitialized) {
                    viewModel.initOnce()
                } else {
                    viewModel.refreshWatchingRows()
                    viewModel.refreshRecentlyAddedRows()
                    viewModel.refreshMovieWatchHistoryRow()
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val state by viewModel.state.collectAsState()
    val loading = state.loadingState
    val refreshing = state.refreshState
    val homeRows = state.homeRows

    when (loading) {
        is LoadingState.Error -> {
            ErrorMessage(loading, modifier)
        }

        LoadingState.Loading,
        LoadingState.Pending,
            -> {
            LoadingPage(modifier)
        }

        LoadingState.Success -> {
            var dialog by remember { mutableStateOf<DialogParams?>(null) }
            var showPlaylistDialog by remember { mutableStateOf<UUID?>(null) }
            val playlistState by playlistViewModel.playlistState.observeAsState(PlaylistLoadingState.Pending)
            var position by rememberPosition()
            HomePageContent(
                homeRows = homeRows,
                position = position,
                onFocusPosition = { position = it },
                onClickItem = { clickedPosition, item ->
                    position = clickedPosition
                    viewModel.navigationManager.navigateTo(item.destination())
                },
                onLongClickItem = { clickedPosition, item ->
                    position = clickedPosition
                    viewModel.setFavorite(item.id, !item.favorite)
                },
                onClickPlay = { _, item ->
                    viewModel.navigationManager.navigateTo(Destination.Playback(item))
                },
                loadingState = refreshing,
                showClock = preferences.appPreferences.interfacePreferences.showClock,
                onUpdateBackdrop = viewModel::updateBackdrop,
                onHideBackdropImage = viewModel::hideBackdropImage,
                modifier = modifier,
            )
            dialog?.let { params ->
                DialogPopup(
                    params = params,
                    onDismissRequest = { dialog = null },
                )
            }
            showPlaylistDialog?.let { itemId ->
                PlaylistDialog(
                    title = stringResource(R.string.add_to_playlist),
                    state = playlistState,
                    onDismissRequest = { showPlaylistDialog = null },
                    onClick = {
                        playlistViewModel.addToPlaylist(it.id, itemId)
                        showPlaylistDialog = null
                    },
                    createEnabled = true,
                    onCreatePlaylist = {
                        playlistViewModel.createPlaylistAndAddItem(it, itemId)
                        showPlaylistDialog = null
                    },
                    elevation = 3.dp,
                )
            }
        }
    }
}

@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomePageContent(
    homeRows: List<HomeRowLoadingState>,
    position: RowColumn,
    onFocusPosition: (RowColumn) -> Unit,
    onClickItem: (RowColumn, BaseItem) -> Unit,
    onLongClickItem: (RowColumn, BaseItem) -> Unit,
    onClickPlay: (RowColumn, BaseItem) -> Unit,
    showClock: Boolean,
    onUpdateBackdrop: (BaseItem) -> Unit,
    onHideBackdropImage: () -> Unit = {},
    modifier: Modifier = Modifier,
    loadingState: LoadingState? = null,
    listState: LazyListState = rememberLazyListState(),
    takeFocus: Boolean = true,
    showEmptyRows: Boolean = false,
) {
    val visibleRowIndices by remember(listState) {
        derivedStateOf {
            listState.layoutInfo.visibleItemsInfo
                .map { it.index }
                .sorted()
        }
    }

    PreloadHomeRowImages(
        homeRows = homeRows,
        visibleRowIndices =
            visibleRowIndices.ifEmpty {
                listOf(listState.firstVisibleItemIndex)
            },
        prioritizedRowIndex = position.row.takeIf { it in homeRows.indices },
        prioritizedColumnIndex = position.column.takeIf { it >= 0 },
    )

    LaunchedEffect(homeRows, position) {
        val clampedPosition =
            when {
                homeRows.isEmpty() -> RowColumn(-1, -1)
                position.row !in homeRows.indices -> {
                    val fallbackRow =
                        position.row.coerceIn(0, homeRows.lastIndex)
                    val fallbackItems = (homeRows[fallbackRow] as? HomeRowLoadingState.Success)?.items.orEmpty()
                    RowColumn(fallbackRow, fallbackItems.lastIndex.coerceAtLeast(0))
                }
                homeRows[position.row] is HomeRowLoadingState.Success -> {
                    val items = (homeRows[position.row] as HomeRowLoadingState.Success).items
                    if (items.isEmpty()) {
                        val nextRow =
                            homeRows.indexOfFirst { it is HomeRowLoadingState.Success && it.items.isNotEmpty() }
                        if (nextRow >= 0) RowColumn(nextRow, 0) else RowColumn(-1, -1)
                    } else {
                        position.copy(column = position.column.coerceIn(0, items.lastIndex))
                    }
                }
                else -> position
            }

        if (clampedPosition != position) {
            onFocusPosition(clampedPosition)
        }
    }

    val focusedItem =
        position.let {
            (homeRows.getOrNull(it.row) as? HomeRowLoadingState.Success)?.items?.getOrNull(it.column)
        }

    val lifecycleOwner = LocalLifecycleOwner.current

    val maxRows = 50
    val rowFocusRequesters = remember { List(maxRows) { FocusRequester() } }
    val fallbackRowIndex =
        when {
            position.row in homeRows.indices -> position.row
            else ->
                homeRows.indexOfFirstOrNull {
                    it is HomeRowLoadingState.Success && it.items.isNotEmpty()
                } ?: 0
        }.coerceIn(0, rowFocusRequesters.lastIndex)
    val fallbackRowFocusRequester = rowFocusRequesters[fallbackRowIndex]
    var firstFocused by remember { mutableStateOf(false) }
    var itemFocusRestoreToken by remember { mutableIntStateOf(0) }
    var pendingRestoreTarget by remember { mutableStateOf<RowColumn?>(null) }
    var suppressTransientFocusUntilRestore by remember { mutableStateOf(false) }
    var awaitingResumeRestore by rememberSaveable { mutableStateOf(false) }
    var lastBackdropItemId by rememberSaveable { mutableStateOf<UUID?>(null) }
    var previousRowSignatures by remember {
        mutableStateOf(
            homeRows.map { row ->
                when (row) {
                    is HomeRowLoadingState.Success -> {
                        "${row.title}:${row.items.filterIsInstance<BaseItem>().joinToString("|") { it.id.toString() }}"
                    }
                    else -> row.title
                }
            },
        )
    }
    var previousFocusedItemId by remember { mutableStateOf<UUID?>(focusedItem?.id) }
    if (takeFocus) {
        LaunchedEffect(homeRows) {
            if (homeRows.isNotEmpty()) {
                val currentRowSignatures =
                    homeRows.map { row ->
                        when (row) {
                            is HomeRowLoadingState.Success -> {
                                "${row.title}:${row.items.filterIsInstance<BaseItem>().joinToString("|") { it.id.toString() }}"
                            }
                            else -> row.title
                        }
                    }
                if (!firstFocused) {
                    if (position.row >= 0) {
                        val index = position.row.coerceIn(0, homeRows.lastIndex)
                        rowFocusRequesters.getOrNull(index)?.tryRequestFocus()
                        firstFocused = true
                    } else {
                        homeRows
                            .indexOfFirstOrNull { it is HomeRowLoadingState.Success && it.items.isNotEmpty() }
                            ?.let {
                                rowFocusRequesters[it].tryRequestFocus()
                                firstFocused = true
                                delay(50)
                                listState.scrollToItem(it)
                            }
                    }
                } else if (currentRowSignatures != previousRowSignatures) {
                    val currentRowIndex = position.row.coerceIn(0, homeRows.lastIndex)
                    val currentFocusedRow = homeRows.getOrNull(currentRowIndex) as? HomeRowLoadingState.Success
                    val currentFocusedItemId = currentFocusedRow?.items?.getOrNull(position.column)?.id
                    val focusedItemMissing =
                        currentFocusedRow
                            ?.items
                            ?.getOrNull(position.column) == null
                    val focusedRowUnavailable = currentFocusedRow == null
                    val focusedItemChanged =
                        previousFocusedItemId != null &&
                            currentFocusedItemId != null &&
                            currentFocusedItemId != previousFocusedItemId

                    if (
                        lifecycleOwner.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) &&
                        (focusedItemMissing || focusedRowUnavailable || focusedItemChanged)
                    ) {
                        pendingRestoreTarget =
                            currentFocusedRow
                                ?.items
                                ?.takeIf { it.isNotEmpty() }
                                ?.let {
                                    RowColumn(
                                        currentRowIndex,
                                        position.column.coerceIn(0, it.lastIndex),
                                    )
                                }
                        itemFocusRestoreToken++
                    }
                    previousFocusedItemId = currentFocusedItemId
                } else {
                    previousFocusedItemId = focusedItem?.id
                }
                previousRowSignatures = currentRowSignatures
            }
        }
        LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
            val index =
                if (position.row >= 0) {
                    position.row.coerceIn(0, homeRows.lastIndex)
                } else {
                    homeRows.indexOfFirstOrNull {
                        it is HomeRowLoadingState.Success && it.items.isNotEmpty()
                    } ?: -1
                }
            if (awaitingResumeRestore && index >= 0) {
                val requestedTarget =
                    pendingRestoreTarget ?: RowColumn(index, position.column)
                val row = homeRows.getOrNull(requestedTarget.row) as? HomeRowLoadingState.Success
                val restoreTarget =
                    row
                        ?.items
                        ?.takeIf { it.isNotEmpty() }
                        ?.let {
                            RowColumn(
                                row = requestedTarget.row,
                                column = requestedTarget.column.coerceIn(0, it.lastIndex),
                            )
                        }

                if (restoreTarget != null) {
                    val restoreAlreadyInPlace =
                        restoreTarget == position &&
                            row.items.getOrNull(restoreTarget.column)?.id == focusedItem?.id

                    if (restoreAlreadyInPlace) {
                        pendingRestoreTarget = null
                        suppressTransientFocusUntilRestore = false
                        awaitingResumeRestore = false
                    } else {
                        pendingRestoreTarget = restoreTarget
                        suppressTransientFocusUntilRestore = true
                        awaitingResumeRestore = false
                        itemFocusRestoreToken++
                    }
                } else {
                    awaitingResumeRestore = false
                    rowFocusRequesters.getOrNull(index)?.tryRequestFocus()
                }
            } else if (suppressTransientFocusUntilRestore && index >= 0) {
                val row = homeRows.getOrNull(index) as? HomeRowLoadingState.Success
                val restoreTarget =
                    row
                        ?.items
                        ?.takeIf { it.isNotEmpty() }
                        ?.let {
                            RowColumn(
                                row = index,
                                column = position.column.coerceIn(0, it.lastIndex),
                            )
                        }

                if (restoreTarget != null) {
                    pendingRestoreTarget = restoreTarget
                    itemFocusRestoreToken++
                } else {
                    suppressTransientFocusUntilRestore = false
                    rowFocusRequesters.getOrNull(index)?.tryRequestFocus()
                }
            } else {
                rowFocusRequesters.getOrNull(index)?.tryRequestFocus()
            }
        }
    }
    LaunchedEffect(position.row) {
        if (position.row >= 0) {
            val firstVisibleRow =
                listState.layoutInfo.visibleItemsInfo.firstOrNull()?.index
                    ?: listState.firstVisibleItemIndex
            val lastVisibleRow =
                listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index
                    ?: firstVisibleRow
            if (position.row !in firstVisibleRow..lastVisibleRow) {
                listState.scrollToItem(position.row)
            }
        }
    }

    LaunchedEffect(focusedItem?.id) {
        val item = focusedItem ?: return@LaunchedEffect
        val backdropAlreadyShowing = lastBackdropItemId == item.id
        if (backdropAlreadyShowing) return@LaunchedEffect
        onHideBackdropImage()
        delay(BACKDROP_FOCUS_SETTLE_DELAY_MS)
        onUpdateBackdrop(item)
        lastBackdropItemId = item.id
    }

    Box(modifier = modifier) {
        Column(modifier = Modifier.fillMaxSize()) {
            HomePageHeader(
                item = focusedItem,
                modifier =
                    Modifier
                        .padding(top = 48.dp, bottom = 32.dp, start = 8.dp)
                        .fillMaxHeight(.33f),
            )
            val density = LocalDensity.current
            val spaceAbovePx =
                with(density) {
                    50.dp.toPx()
                }
            val defaultBringIntoViewSpec = LocalBringIntoViewSpec.current
            CompositionLocalProvider(
                LocalBringIntoViewSpec provides ScrollToTopBringIntoViewSpec(spaceAbovePx),
            ) {
                LazyColumn(
                    state = listState,
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    contentPadding =
                        PaddingValues(
                            bottom = Cards.height2x3,
                        ),
                        modifier =
                            Modifier
                                .focusGroup()
                                .focusProperties {
                                    onEnter = {
                                        fallbackRowFocusRequester.tryRequestFocus()
                                    }
                                },
                ) {
                    itemsIndexed(
                        items = homeRows,
                        key = { index, row -> "${row.title}_$index" },
                    ) { rowIndex, row ->
                        CompositionLocalProvider(
                            LocalBringIntoViewSpec provides defaultBringIntoViewSpec,
                        ) {
                            when (row) {
                                is HomeRowLoadingState.Loading,
                                is HomeRowLoadingState.Pending,
                                    -> {
                                    FocusableItemRow(
                                        title = row.title,
                                        subtitle = stringResource(R.string.loading),
                                        modifier = Modifier,
                                    )
                                }

                                is HomeRowLoadingState.Error -> {
                                    FocusableItemRow(
                                        title = row.title,
                                        subtitle = row.localizedMessage,
                                        isError = true,
                                        modifier = Modifier,
                                    )
                                }

                                is HomeRowLoadingState.Success -> {
                                    if (row.items.isNotEmpty()) {
                                        val viewOptions = row.viewOptions
                                        ItemRow(
                                            title = row.title,
                                            items = row.items,
                                            onClickItem = { index, item ->
                                                pendingRestoreTarget = RowColumn(rowIndex, index)
                                                suppressTransientFocusUntilRestore = true
                                                awaitingResumeRestore = true
                                                onClickItem.invoke(RowColumn(rowIndex, index), item)
                                            },
                                            onLongClickItem = { index, item ->
                                                onLongClickItem.invoke(
                                                    RowColumn(rowIndex, index),
                                                    item,
                                                )
                                            },
                                            preferredIndex =
                                                when {
                                                    pendingRestoreTarget?.row == rowIndex -> pendingRestoreTarget?.column
                                                    rowIndex == position.row -> position.column
                                                    else -> null
                                                },
                                            focusRestoreToken =
                                                if (pendingRestoreTarget?.row == rowIndex) {
                                                    itemFocusRestoreToken
                                                } else {
                                                    -1
                                                },
                                            modifier =
                                                Modifier
                                                    .fillMaxWidth()
                                                    .focusGroup()
                                                    .focusRequester(rowFocusRequesters[rowIndex]),
                                            horizontalPadding = viewOptions.spacing.dp,
                                            cardContent = { index, item, cardModifier, onClick, onLongClick ->
                                                HomePageCardContent(
                                                    index = index,
                                                    item = item,
                                                    onClick = onClick,
                                                    onLongClick = onLongClick,
                                                    viewOptions = viewOptions,
                                                    modifier =
                                                        cardModifier
                                                            .onFocusChanged {
                                                                if (it.isFocused) {
                                                                    val restoreTarget = pendingRestoreTarget
                                                                    val matchesRestoreTarget =
                                                                        restoreTarget == null ||
                                                                            (
                                                                                restoreTarget.row == rowIndex &&
                                                                                    restoreTarget.column == index
                                                                            )
                                                                    val isTransientReturnHop =
                                                                        suppressTransientFocusUntilRestore &&
                                                                            !matchesRestoreTarget

                                                                    if (awaitingResumeRestore) {
                                                                    } else if (!isTransientReturnHop && matchesRestoreTarget) {
                                                                        if (restoreTarget != null) {
                                                                            pendingRestoreTarget = null
                                                                        }
                                                                        awaitingResumeRestore = false
                                                                        if (suppressTransientFocusUntilRestore) {
                                                                            suppressTransientFocusUntilRestore = false
                                                                        }
                                                                        onFocusPosition.invoke(
                                                                            RowColumn(rowIndex, index),
                                                                        )
                                                                    }
                                                                }
                                                            }.onKeyEvent {
                                                                if (isPlayKeyUp(it) && item?.type?.playable == true) {
                                                                    Timber.v("Clicked play on ${item.id}")
                                                                    pendingRestoreTarget = RowColumn(rowIndex, index)
                                                                    awaitingResumeRestore = true
                                                                    suppressTransientFocusUntilRestore = true
                                                                    onClickPlay.invoke(
                                                                        RowColumn(rowIndex, index),
                                                                        item,
                                                                    )
                                                                    return@onKeyEvent true
                                                                }
                                                                return@onKeyEvent false
                                                            },
                                                )
                                            },
                                        )
                                    } else if (showEmptyRows) {
                                        FocusableItemRow(
                                            title = row.title,
                                            subtitle = stringResource(R.string.no_results),
                                            modifier = Modifier,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
        when (loadingState) {
            LoadingState.Pending,
            LoadingState.Loading,
                -> {
                Box(
                    modifier =
                        Modifier
                            .padding(if (showClock) 40.dp else 20.dp)
                            .size(40.dp)
                            .align(Alignment.TopEnd),
                ) {
                    CircularProgress(Modifier.fillMaxSize())
                }
            }

            else -> {}
        }
    }
}

@Composable
fun HomePageHeader(
    item: BaseItem?,
    modifier: Modifier = Modifier,
) {
    val isEpisode = item?.type == BaseItemKind.EPISODE
    val dto = item?.data
    HomePageHeader(
        title = item?.title,
        subtitle = if (isEpisode) dto?.name else null,
        overview = dto?.overview,
        overviewTwoLines = isEpisode,
        quickDetails = item?.ui?.quickDetails ?: AnnotatedString(""),
        timeRemaining = item?.timeRemainingOrRuntime,
        modifier = modifier,
    )
}

@Composable
fun HomePageHeader(
    title: String?,
    subtitle: String?,
    overview: String?,
    overviewTwoLines: Boolean,
    quickDetails: AnnotatedString?,
    timeRemaining: Duration?,
    modifier: Modifier = Modifier,
) {
    Column(
        verticalArrangement = Arrangement.spacedBy(4.dp),
        modifier = modifier,
    ) {
        title?.let {
            Text(
                text = it,
                style = MaterialTheme.typography.headlineMedium.copy(fontWeight = FontWeight.SemiBold),
                color = MaterialTheme.colorScheme.onBackground,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.fillMaxWidth(.75f),
            )
        }
        Column(
            verticalArrangement = Arrangement.spacedBy(8.dp),
            modifier =
                Modifier
                    .fillMaxWidth(.6f)
                    .fillMaxHeight(),
        ) {
            subtitle?.let {
                EpisodeName(it)
            }
            QuickDetails(quickDetails ?: AnnotatedString(""), timeRemaining)
            val overviewModifier =
                Modifier
                    .padding(0.dp)
                    .height(48.dp + if (!overviewTwoLines) 12.dp else 0.dp)
                    .width(400.dp)
            if (overview.isNotNullOrBlank()) {
                Text(
                    text = overview,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface,
                    maxLines = if (overviewTwoLines) 2 else 3,
                    overflow = TextOverflow.Ellipsis,
                    modifier = overviewModifier,
                )
            } else {
                Spacer(overviewModifier)
            }
        }
    }
}

@Composable
fun HomePageCardContent(
    index: Int,
    item: BaseItem?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    viewOptions: HomeRowViewOptions,
    modifier: Modifier,
) {
    when (item?.type) {
        BaseItemKind.GENRE -> {
            GenreCard(
                genreId = item.id,
                name = item.name,
                imageUrl = item.imageUrlOverride,
                onClick = onClick,
                onLongClick = onLongClick,
                modifier = modifier.height(viewOptions.heightDp.dp),
            )
        }

        else -> {
            val ticks = item?.data?.userData?.playbackPositionTicks ?: 0L
            val isInterceptedEpisode = item?.type == BaseItemKind.EPISODE && ticks > 0L

            val imageType =
                remember(item, viewOptions, isInterceptedEpisode) {
                    if (isInterceptedEpisode) {
                        org.jellyfin.sdk.model.api.ImageType.PRIMARY
                    } else if (item?.type == BaseItemKind.EPISODE) {
                        viewOptions.episodeImageType.imageType
                    } else {
                        viewOptions.imageType.imageType
                    }
                }

            val ratio =
                remember(item, viewOptions, isInterceptedEpisode) {
                    if (isInterceptedEpisode) {
                        16f / 9f
                    } else if (item?.type == BaseItemKind.EPISODE) {
                        viewOptions.episodeAspectRatio.ratio
                    } else {
                        viewOptions.aspectRatio.ratio
                    }
                }

            val scale =
                remember(item, viewOptions, isInterceptedEpisode) {
                    if (isInterceptedEpisode) {
                        androidx.compose.ui.layout.ContentScale.Crop
                    } else if (item?.type == BaseItemKind.EPISODE) {
                        viewOptions.episodeContentScale.scale
                    } else {
                        viewOptions.contentScale.scale
                    }
                }

            if (viewOptions.showTitles) {
                BannerCardWithTitle(
                    title = item?.title,
                    subtitle = item?.subtitle,
                    item = item,
                    aspectRatio = ratio,
                    imageType = imageType,
                    imageContentScale = scale,
                    cornerText = item?.ui?.episodeUnplayedCornerText,
                    played = item?.data?.userData?.played ?: false,
                    favorite = item?.favorite ?: false,
                    playPercent =
                        item?.data?.userData?.playedPercentage
                            ?: 0.0,
                    onClick = onClick,
                    onLongClick = onLongClick,
                    modifier = modifier,
                    cardHeight = viewOptions.heightDp.dp,
                )
            } else {
                BannerCard(
                    name = item?.data?.seriesName ?: item?.name,
                    item = item,
                    aspectRatio = ratio,
                    imageType = imageType,
                    imageContentScale = scale,
                    cornerText = item?.ui?.episodeUnplayedCornerText,
                    played = item?.data?.userData?.played ?: false,
                    favorite = item?.favorite ?: false,
                    playPercent =
                        item?.data?.userData?.playedPercentage
                            ?: 0.0,
                    onClick = onClick,
                    onLongClick = onLongClick,
                    modifier = modifier,
                    interactionSource = null,
                    cardHeight = viewOptions.heightDp.dp,
                )
            }
        }
    }
}
