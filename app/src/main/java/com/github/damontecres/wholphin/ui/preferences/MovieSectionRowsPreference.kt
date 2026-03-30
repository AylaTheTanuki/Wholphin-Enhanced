package com.github.damontecres.wholphin.ui.preferences

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.tv.material3.ListItem
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.ServerPreferencesDao
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.MovieSectionRowPreference
import com.github.damontecres.wholphin.ui.FontAwesome
import com.github.damontecres.wholphin.ui.components.BasicDialog
import com.github.damontecres.wholphin.ui.components.Button
import com.github.damontecres.wholphin.ui.components.RecommendedMovieViewModel
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.util.ExceptionHandler
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import javax.inject.Inject

data class MovieSectionRowSetting(
    val id: String,
    val title: String,
)

private fun <T> List<T>.move(
    direction: MoveDirection,
    index: Int,
): List<T> =
    toMutableList().apply {
        if (direction == MoveDirection.DOWN) {
            val current = this[index]
            val next = this[index + 1]
            set(index, next)
            set(index + 1, current)
        } else {
            val current = this[index]
            val previous = this[index - 1]
            set(index - 1, current)
            set(index, previous)
        }
    }

@Composable
fun MovieSectionRowsPreference(
    title: String,
    summary: String?,
    modifier: Modifier = Modifier,
    viewModel: MovieSectionRowsPreferenceViewModel = hiltViewModel(),
) {
    val items by viewModel.state.collectAsState()
    var showDialog by remember { mutableStateOf(false) }

    ClickPreference(
        title = title,
        summary = summary,
        onClick = { showDialog = true },
        modifier = modifier,
    )

    if (showDialog) {
        MovieSectionRowsPreferenceDialog(
            items = items,
            onDismissRequest = {
                viewModel.save()
                showDialog = false
            },
            onMoveUp = { index ->
                viewModel.update(items.move(MoveDirection.UP, index))
            },
            onMoveDown = { index ->
                viewModel.update(items.move(MoveDirection.DOWN, index))
            },
        )
    }
}

@Composable
fun MovieSectionRowsPreferenceDialog(
    items: List<MovieSectionRowSetting>,
    onDismissRequest: () -> Unit,
    onMoveUp: (Int) -> Unit,
    onMoveDown: (Int) -> Unit,
) {
    BasicDialog(
        onDismissRequest = onDismissRequest,
        elevation = 3.dp,
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
        ) {
            Text(
                text = stringResource(R.string.movie_section_rows),
                style = MaterialTheme.typography.titleLarge,
                color = MaterialTheme.colorScheme.onSurface,
                modifier = Modifier.padding(bottom = 8.dp),
            )
            LazyColumn(
                state = rememberLazyListState(),
                verticalArrangement = Arrangement.spacedBy(0.dp),
            ) {
                itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                    Row(
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically,
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .heightIn(min = 40.dp, max = 88.dp),
                    ) {
                        ListItem(
                            selected = false,
                            headlineContent = {
                                Text(text = item.title)
                            },
                            onClick = {},
                            modifier = Modifier.weight(1f),
                        )
                        Row(
                            horizontalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            MovieRowMoveButton(
                                icon = R.string.fa_caret_up,
                                enabled = index > 0,
                                onClick = { onMoveUp(index) },
                            )
                            MovieRowMoveButton(
                                icon = R.string.fa_caret_down,
                                enabled = index < items.lastIndex,
                                onClick = { onMoveDown(index) },
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MovieRowMoveButton(
    icon: Int,
    enabled: Boolean,
    onClick: () -> Unit,
) = Button(
    onClick = onClick,
    enabled = enabled,
    modifier = Modifier.size(32.dp),
) {
    Text(
        text = stringResource(icon),
        fontSize = 16.sp,
        fontFamily = FontAwesome,
        textAlign = TextAlign.Center,
        modifier = Modifier.fillMaxWidth(),
    )
}

@HiltViewModel
class MovieSectionRowsPreferenceViewModel
    @Inject
    constructor(
        @ApplicationContext private val context: Context,
        private val serverRepository: ServerRepository,
        private val serverPreferencesDao: ServerPreferencesDao,
    ) : ViewModel() {
        val state = MutableStateFlow<List<MovieSectionRowSetting>>(emptyList())

        init {
            viewModelScope.launchIO {
                val user = serverRepository.currentUser.value ?: return@launchIO
                val savedOrder =
                    serverPreferencesDao
                        .getMovieSectionRowPreferences(user.rowId)
                        .map { it.rowId }
                state.value =
                    RecommendedMovieViewModel
                        .orderedRowsForSettings(savedOrder)
                        .map {
                            MovieSectionRowSetting(it.dbId, it.title(context))
                        }
            }
        }

        fun update(items: List<MovieSectionRowSetting>) {
            state.update { items }
        }

        fun save() {
            viewModelScope.launchIO(ExceptionHandler(true)) {
                val user = serverRepository.currentUser.value ?: return@launchIO
                val items =
                    state.value.mapIndexed { index, item ->
                        MovieSectionRowPreference(
                            userId = user.rowId,
                            rowId = item.id,
                            order = index,
                        )
                    }
                serverPreferencesDao.saveMovieSectionRowPreferences(*items.toTypedArray())
            }
        }
    }
