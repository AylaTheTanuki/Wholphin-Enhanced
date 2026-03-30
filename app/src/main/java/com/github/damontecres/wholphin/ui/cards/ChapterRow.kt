package com.github.damontecres.wholphin.ui.cards

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.model.Chapter
import com.github.damontecres.wholphin.ui.rememberInt
import com.github.damontecres.wholphin.ui.tryRequestFocus

@Composable
fun ChapterRow(
    chapters: List<Chapter>,
    aspectRatio: Float,
    onClick: (Chapter) -> Unit,
    modifier: Modifier = Modifier,
    onLongClick: ((Chapter) -> Unit)? = null,
) {
    val firstFocus = remember { FocusRequester() }
    val titleText = stringResource(R.string.chapters)
    var position by rememberInt()
    var suppressTransientFocusUntilRestore by rememberSaveable { mutableStateOf(false) }
    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            modifier.focusProperties {
                onEnter = {
                    firstFocus.tryRequestFocus("chapter_row_enter:$position")
                }
            },
    ) {
        Text(
            text = titleText,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier.padding(start = 8.dp),
        )
        LazyRow(
            state = rememberLazyListState(),
            horizontalArrangement = Arrangement.spacedBy(16.dp),
            contentPadding = PaddingValues(vertical = 8.dp, horizontal = 16.dp),
            modifier =
                Modifier
                    .fillMaxWidth(),
        ) {
            itemsIndexed(chapters) { index, item ->
                ChapterCard(
                    name = item.name,
                    position = item.position,
                    imageUrl = item.imageUrl,
                    onClick = {
                        position = index
                        suppressTransientFocusUntilRestore = true
                        onClick(item)
                    },
                    aspectRatio = aspectRatio,
                    modifier =
                        Modifier
                            .onFocusChanged {
                                if (!it.isFocused) return@onFocusChanged

                                if (suppressTransientFocusUntilRestore) {
                                    if (index != position) return@onFocusChanged
                                    suppressTransientFocusUntilRestore = false
                                }

                                position = index
                            }.let {
                                if (index == position) {
                                    it.focusRequester(firstFocus)
                                } else {
                                    it
                                }
                            },
                    onLongClick = onLongClick?.let { { it(item) } },
                )
            }
        }
    }
}
