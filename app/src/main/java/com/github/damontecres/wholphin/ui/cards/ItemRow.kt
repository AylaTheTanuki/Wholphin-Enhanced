package com.github.damontecres.wholphin.ui.cards

import androidx.compose.foundation.focusGroup
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.ui.rememberInt
import com.github.damontecres.wholphin.ui.tryRequestFocus
import com.github.damontecres.wholphin.ui.tryRequestFocusAfterLayout
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

@Composable
fun <T> ItemRow(
    title: String,
    items: List<T?>,
    onClickItem: (Int, T) -> Unit,
    onLongClickItem: (Int, T) -> Unit,
    preferredIndex: Int? = null,
    focusRestoreToken: Int = -1,
    onItemFocused: ((T) -> Unit)? = null, // OPTIMIZATION 2: Optional Debounce Callback
    cardContent: @Composable (
        index: Int,
        item: T?,
        modifier: Modifier,
        onClick: () -> Unit,
        onLongClick: () -> Unit,
    ) -> Unit,
    modifier: Modifier = Modifier,
    horizontalPadding: Dp = 16.dp,
) {
    val state = rememberLazyListState()
    val firstFocus = remember { FocusRequester() }
    val focusRequester = remember { FocusRequester() }
    val scope = rememberCoroutineScope()
    var storedPosition by rememberInt()
    var suppressTransientFocusUntilRestore by rememberSaveable { mutableStateOf(false) }
    var lastHandledFocusRestoreToken by rememberSaveable { mutableIntStateOf(focusRestoreToken) }
    var navigationRestoreIndex by rememberSaveable { mutableIntStateOf(-1) }
    var awaitingResumeRestore by rememberSaveable { mutableStateOf(false) }
    var restoringAfterResume by rememberSaveable { mutableStateOf(false) }
    val targetPosition =
        if (items.isEmpty()) {
            0
        } else {
            (preferredIndex ?: storedPosition).coerceIn(0, items.lastIndex)
        }

    LaunchedEffect(items.size, preferredIndex) {
        if (items.isEmpty() || preferredIndex == null) return@LaunchedEffect
        storedPosition = preferredIndex.coerceIn(0, items.lastIndex)
    }

    LaunchedEffect(focusRestoreToken, targetPosition, items.size) {
        if (items.isEmpty()) return@LaunchedEffect
        if (focusRestoreToken < 0) return@LaunchedEffect
        if (focusRestoreToken == lastHandledFocusRestoreToken) return@LaunchedEffect

        lastHandledFocusRestoreToken = focusRestoreToken
        storedPosition = targetPosition
        suppressTransientFocusUntilRestore = true
        firstFocus.tryRequestFocusAfterLayout("item_row_restore:$title:$targetPosition")
    }

    LifecycleEventEffect(Lifecycle.Event.ON_RESUME) {
        if (awaitingResumeRestore && navigationRestoreIndex in items.indices) {
            storedPosition = navigationRestoreIndex
            suppressTransientFocusUntilRestore = true
            restoringAfterResume = true
            awaitingResumeRestore = false
            scope.launch {
                firstFocus.tryRequestFocusAfterLayout("item_row_resume:$title:$navigationRestoreIndex")
            }
        }
    }

    // --- OPTIMIZATION 2: THE DEBOUNCE TRIGGER ---
    // Every time the user scrolls to a new card, this timer restarts.
    // If they keep scrolling fast, it cancels silently.
    // If they stop on a card for 150ms, it fires the background task!
    LaunchedEffect(targetPosition) {
        if (onItemFocused != null) {
            delay(150) // The magic 150ms buffer
            val focusedItem = items.getOrNull(targetPosition)
            if (focusedItem != null) {
                onItemFocused.invoke(focusedItem)
            }
        }
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(8.dp),
        modifier =
            modifier.focusProperties {
                onEnter = {
                    if (!firstFocus.tryRequestFocus("item_row_enter:$title:$targetPosition")) {
                        focusRequester.tryRequestFocus("item_row_enter:$title")
                    }
                }
            },
    ) {
        Text(
            text = title,
            style = MaterialTheme.typography.titleLarge,
            color = MaterialTheme.colorScheme.onBackground,
            modifier = Modifier,
        )
        LazyRow(
            state = state,
            horizontalArrangement = Arrangement.spacedBy(horizontalPadding),
            contentPadding = PaddingValues(horizontal = horizontalPadding, vertical = 8.dp),
            modifier =
                Modifier
                    .fillMaxWidth()
                    .focusGroup()
                    .focusRequester(focusRequester),
        ) {
            itemsIndexed(items) { index, item ->
                val cardModifier =
                    if (index == targetPosition) {
                        Modifier.focusRequester(firstFocus)
                    } else {
                        Modifier
                    }
                val managedCardModifier =
                    cardModifier.onFocusChanged {
                        if (!it.isFocused) return@onFocusChanged

                        if (awaitingResumeRestore) {
                            return@onFocusChanged
                        }

                        if (restoringAfterResume) {
                            if (index != storedPosition) return@onFocusChanged
                            restoringAfterResume = false
                        }

                        if (suppressTransientFocusUntilRestore) {
                            if (index != storedPosition) return@onFocusChanged
                            suppressTransientFocusUntilRestore = false
                        }

                        storedPosition = index
                    }
                cardContent.invoke(
                    index,
                    item,
                    managedCardModifier,
                    {
                        storedPosition = index
                        navigationRestoreIndex = index
                        awaitingResumeRestore = true
                        restoringAfterResume = false
                        suppressTransientFocusUntilRestore = true
                        if (item != null) onClickItem.invoke(index, item)
                    },
                    {
                        storedPosition = index
                        if (item != null) onLongClickItem.invoke(index, item)
                    },
                )
            }
        }
    }
}
