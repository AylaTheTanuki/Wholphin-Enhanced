package com.github.damontecres.wholphin.ui.components

import android.annotation.SuppressLint
import android.content.Context
import androidx.annotation.StringRes
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.livedata.observeAsState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.FavoriteWatchManager
import com.github.damontecres.wholphin.services.MediaReportService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.ui.data.AddPlaylistViewModel
import com.github.damontecres.wholphin.ui.data.RowColumn
import com.github.damontecres.wholphin.ui.detail.MoreDialogActions
import com.github.damontecres.wholphin.ui.detail.PlaylistDialog
import com.github.damontecres.wholphin.ui.detail.PlaylistLoadingState
import com.github.damontecres.wholphin.ui.detail.buildMoreDialogItemsForHome
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.main.HomePageContent
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.rememberPosition
import com.github.damontecres.wholphin.util.ApiRequestPager
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import com.github.damontecres.wholphin.util.LoadingState
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.api.MediaType
import timber.log.Timber
import java.util.UUID

@SuppressLint("StaticFieldLeak")
abstract class RecommendedViewModel(
    val context: Context,
    val navigationManager: NavigationManager,
    val favoriteWatchManager: FavoriteWatchManager,
    val mediaReportService: MediaReportService,
    private val backdropService: BackdropService,
    val api: ApiClient,
    val serverRepository: ServerRepository,
) : ViewModel() {
    abstract fun init()

    abstract val rows: MutableStateFlow<List<HomeRowLoadingState>>

    val loading = MutableLiveData<LoadingState>(LoadingState.Loading)

    fun refreshItem(
        position: RowColumn,
        itemId: UUID,
        newFavoriteStatus: Boolean? = null
    ) {
        viewModelScope.launchIO {
            val currentRowList = rows.value.toMutableList()
            val rowState = currentRowList.getOrNull(position.row)

            if (rowState is HomeRowLoadingState.Success) {
                if (rowState.items is ApiRequestPager<*>) {
                    (rowState.items as ApiRequestPager<*>).refreshItem(position.column, itemId)
                } else {
                    val oldList = rowState.items.filterIsInstance<BaseItem>()
                    if (oldList.isNotEmpty() && newFavoriteStatus != null) {
                        val userId = serverRepository.currentUser.value?.id
                        if (userId != null) {
                            try {
                                val freshItemDto = api.userLibraryApi.getItem(userId = userId, itemId = itemId).content
                                val newList = oldList.map { item ->
                                    if (item.id == itemId) {
                                        freshItemDto.userData?.let { ud ->
                                            try {
                                                val field = ud::class.java.getDeclaredField("isFavorite")
                                                field.isAccessible = true
                                                field.set(ud, newFavoriteStatus)
                                            } catch (e: Exception) { Timber.e(e) }
                                        }
                                        item.copy(data = freshItemDto)
                                    } else {
                                        item
                                    }
                                }
                                currentRowList[position.row] = rowState.copy(items = newList)
                                rows.update { currentRowList.toList() }
                            } catch (e: Exception) {
                                Timber.e(e, "Failed to fetch updated item from API")
                            }
                        }
                    }
                }
            }
        }
    }

    fun setWatched(position: RowColumn, itemId: UUID, watched: Boolean) {
        viewModelScope.launchIO {
            favoriteWatchManager.setWatched(itemId, watched)
            init()
        }
    }

    fun setFavorite(position: RowColumn, itemId: UUID, favorite: Boolean) {
        viewModelScope.launchIO {
            favoriteWatchManager.setFavorite(itemId, favorite)
            refreshItem(position, itemId, favorite)
            init()
        }
    }

    fun updateBackdrop(item: BaseItem) {
        viewModelScope.launchIO { backdropService.submit(item) }
    }

    abstract fun update(@StringRes title: Int, row: HomeRowLoadingState): HomeRowLoadingState

    fun update(@StringRes title: Int, block: suspend () -> List<BaseItem>): Deferred<HomeRowLoadingState> =
        viewModelScope.async(Dispatchers.IO) {
            val titleStr = context.getString(title)
            val row = try { HomeRowLoadingState.Success(titleStr, block.invoke()) }
            catch (ex: Exception) { HomeRowLoadingState.Error(titleStr, null, ex) }
            update(title, row)
        }
}

@Composable
fun RecommendedContent(
    preferences: UserPreferences,
    viewModel: RecommendedViewModel,
    modifier: Modifier = Modifier,
    playlistViewModel: AddPlaylistViewModel = hiltViewModel(),
    onFocusPosition: ((RowColumn) -> Unit)? = null,
) {
    val context = LocalContext.current
    var moreDialog by remember { mutableStateOf<Optional<RowColumnItem>>(Optional.absent()) }
    var showPlaylistDialog by remember { mutableStateOf<Optional<UUID>>(Optional.absent()) }
    val playlistState by playlistViewModel.playlistState.observeAsState(PlaylistLoadingState.Pending)

    // THE FIX: Replaced OneTimeLaunchedEffect with a Lifecycle Observer
    // This forces the page to silently reload 'Next Up' and 'Favorites' every time you return from the video player!
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                viewModel.init()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
        }
    }

    val loading by viewModel.loading.observeAsState(LoadingState.Loading)
    val rows by viewModel.rows.collectAsState()

    when (val state = loading) {
        is LoadingState.Error -> ErrorMessage(state, modifier)
        LoadingState.Loading, LoadingState.Pending -> LoadingPage(modifier)
        LoadingState.Success -> {
            var position by rememberPosition()

            val activeRowPairs = remember(rows) {
                rows.mapIndexedNotNull { originalIndex, rowState ->
                    val isEmptySuccess = rowState is HomeRowLoadingState.Success && rowState.items.isEmpty()
                    if (!isEmptySuccess) originalIndex to rowState else null
                }
            }
            val activeRows = activeRowPairs.map { it.second }

            val mapPosition = { pos: RowColumn ->
                val originalRowIndex = activeRowPairs.getOrNull(pos.row)?.first ?: pos.row
                pos.copy(row = originalRowIndex)
            }

            HomePageContent(
                homeRows = activeRows,
                position = position,
                onClickItem = { _, item -> viewModel.navigationManager.navigateTo(item.destination()) },
                onLongClickItem = { pos, item ->
                    val mappedPos = mapPosition(pos)
                    val newFavoriteStatus = !item.favorite
                    viewModel.setFavorite(mappedPos, item.id, newFavoriteStatus)
                },
                onClickPlay = { _, item -> viewModel.navigationManager.navigateTo(Destination.Playback(item)) },
                onFocusPosition = { pos ->
                    position = pos
                    onFocusPosition?.invoke(mapPosition(pos))
                },
                showClock = preferences.appPreferences.interfacePreferences.showClock,
                onUpdateBackdrop = viewModel::updateBackdrop,
                modifier = modifier,
            )
        }
    }

    moreDialog.compose { (pos, item) ->
        DialogPopup(
            showDialog = true,
            title = item.title ?: "",
            dialogItems = buildMoreDialogItemsForHome(
                context = context,
                item = item,
                seriesId = null,
                playbackPosition = item.playbackPosition,
                watched = item.played,
                favorite = item.favorite,
                actions = MoreDialogActions(
                    navigateTo = { viewModel.navigationManager.navigateTo(it) },
                    onClickWatch = { itemId, watched -> viewModel.setWatched(pos, itemId, watched) },
                    onClickFavorite = { itemId, favorite -> viewModel.setFavorite(pos, itemId, favorite) },
                    onClickAddPlaylist = {
                        playlistViewModel.loadPlaylists(MediaType.VIDEO)
                        showPlaylistDialog.makePresent(it)
                    },
                    onSendMediaInfo = viewModel.mediaReportService::sendReportFor,
                ),
            ),
            onDismissRequest = { moreDialog.makeAbsent() },
            dismissOnClick = true,
            waitToLoad = true,
        )
    }

    showPlaylistDialog.compose { itemId ->
        PlaylistDialog(
            title = stringResource(R.string.add_to_playlist),
            state = playlistState,
            onDismissRequest = { showPlaylistDialog.makeAbsent() },
            onClick = { playlist ->
                playlistViewModel.addToPlaylist(playlist.id, itemId)
                showPlaylistDialog.makeAbsent()
            },
            createEnabled = true,
            onCreatePlaylist = { playlistName ->
                playlistViewModel.createPlaylistAndAddItem(playlistName, itemId)
                showPlaylistDialog.makeAbsent()
            },
            elevation = 3.dp,
        )
    }
}

private data class RowColumnItem(
    val position: RowColumn,
    val item: BaseItem,
)