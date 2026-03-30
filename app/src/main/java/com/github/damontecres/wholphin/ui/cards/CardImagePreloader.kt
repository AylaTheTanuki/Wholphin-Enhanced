package com.github.damontecres.wholphin.ui.cards

import android.content.Context
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.crossfade
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.services.ImageUrlService
import com.github.damontecres.wholphin.ui.AspectRatios
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import timber.log.Timber
import kotlin.math.roundToInt

private const val LOOK_AHEAD_ROW_COUNT = 0
private const val LOOK_BEHIND_ROW_COUNT = 0
private const val PRELOAD_START_DELAY_MS = 200L
private const val PRELOAD_BETWEEN_IMAGES_DELAY_MS = 20L
private const val MAX_TRACKED_PRELOAD_URLS = 600

data class CardImagePreloadTarget(
    val imageUrl: String,
    val widthPx: Int? = null,
    val heightPx: Int? = null,
)

fun buildCardImageRequest(
    context: Context,
    imageUrl: String,
    widthPx: Int? = null,
    heightPx: Int? = null,
    crossfade: Boolean = false,
): ImageRequest {
    val builder =
        ImageRequest
            .Builder(context)
            .data(imageUrl)
            .memoryCacheKey(imageUrl)
            .diskCacheKey(imageUrl)
            .crossfade(crossfade)
    if (widthPx != null && heightPx != null) {
        builder.size(widthPx, heightPx)
    }
    return builder.build()
}

@Composable
fun PreloadHomeRowImages(
    homeRows: List<HomeRowLoadingState>,
    visibleRowIndices: List<Int>,
    prioritizedRowIndex: Int? = null,
    prioritizedColumnIndex: Int? = null,
) {
    if (homeRows.isEmpty()) return

    val context = LocalContext.current
    val imageUrlService = LocalImageUrlService.current
    val density = LocalDensity.current
    val preloader = remember(context) { PageImagePreloader(context.applicationContext) }

    DisposableEffect(preloader) {
        onDispose {
            preloader.close()
        }
    }

    val effectiveVisibleRows =
        visibleRowIndices
            .filter { it in homeRows.indices }
            .sorted()
            .ifEmpty { listOf(0) }

    LaunchedEffect(homeRows, effectiveVisibleRows, prioritizedRowIndex, prioritizedColumnIndex) {
        preloader.submitRows(
            buildPreloadRows(
                homeRows = homeRows,
                visibleRowIndices = effectiveVisibleRows,
                prioritizedRowIndex = prioritizedRowIndex,
                prioritizedColumnIndex = prioritizedColumnIndex,
                imageUrlService = imageUrlService,
                densityScale = density.density,
            ),
        )
    }
}

private fun buildPreloadRows(
    homeRows: List<HomeRowLoadingState>,
    visibleRowIndices: List<Int>,
    prioritizedRowIndex: Int?,
    prioritizedColumnIndex: Int?,
    imageUrlService: ImageUrlService,
    densityScale: Float,
): List<List<CardImagePreloadTarget>> {
    if (homeRows.isEmpty()) return emptyList()

    val prioritizedRow =
        prioritizedRowIndex
            ?.takeIf { it in homeRows.indices }
            ?.let { index ->
                (homeRows[index] as? HomeRowLoadingState.Success)
                    ?.toOrderedPreloadTargets(
                        imageUrlService = imageUrlService,
                        densityScale = densityScale,
                        startIndex = prioritizedColumnIndex ?: 0,
                    )?.takeIf { it.isNotEmpty() }
            }

    val firstVisible = visibleRowIndices.firstOrNull()?.coerceIn(0, homeRows.lastIndex) ?: 0
    val lastVisible = visibleRowIndices.lastOrNull()?.coerceIn(0, homeRows.lastIndex) ?: firstVisible

    val rowsBelow =
        ((lastVisible + 1)..homeRows.lastIndex)
            .mapNotNull { index ->
                if (index == prioritizedRowIndex) {
                    return@mapNotNull null
                }
                (homeRows[index] as? HomeRowLoadingState.Success)
                    ?.toPreloadTargets(imageUrlService, densityScale)
                    ?.takeIf { it.isNotEmpty() }
            }.take(LOOK_AHEAD_ROW_COUNT)

    val rowsAbove =
        ((firstVisible - 1) downTo 0)
            .mapNotNull { index ->
                if (index == prioritizedRowIndex) {
                    return@mapNotNull null
                }
                (homeRows[index] as? HomeRowLoadingState.Success)
                    ?.toPreloadTargets(imageUrlService, densityScale)
                    ?.takeIf { it.isNotEmpty() }
            }.take(LOOK_BEHIND_ROW_COUNT)

    return buildList {
        prioritizedRow?.let(::add)
        addAll(rowsBelow)
        addAll(rowsAbove)
    }
}

private fun HomeRowLoadingState.Success.toPreloadTargets(
    imageUrlService: ImageUrlService,
    densityScale: Float,
): List<CardImagePreloadTarget> {
    val cardHeightPx = (viewOptions.heightDp * densityScale).roundToInt().coerceAtLeast(1)
    return items.mapNotNull { item ->
        item?.toPreloadTarget(
            viewOptions = viewOptions,
            cardHeightPx = cardHeightPx,
            imageUrlService = imageUrlService,
        )
    }
}

private fun HomeRowLoadingState.Success.toOrderedPreloadTargets(
    imageUrlService: ImageUrlService,
    densityScale: Float,
    startIndex: Int,
): List<CardImagePreloadTarget> {
    if (items.isEmpty()) return emptyList()

    val cardHeightPx = (viewOptions.heightDp * densityScale).roundToInt().coerceAtLeast(1)
    val safeStartIndex = startIndex.coerceIn(0, items.lastIndex)
    val orderedIndices =
        (safeStartIndex..items.lastIndex).asSequence() +
            (0 until safeStartIndex).asSequence()

    return orderedIndices.mapNotNull { index ->
        items[index]?.toPreloadTarget(
            viewOptions = viewOptions,
            cardHeightPx = cardHeightPx,
            imageUrlService = imageUrlService,
        )
    }.toList()
}

private fun BaseItem.toPreloadTarget(
    viewOptions: HomeRowViewOptions,
    cardHeightPx: Int,
    imageUrlService: ImageUrlService,
): CardImagePreloadTarget? {
    if (type == BaseItemKind.GENRE) {
        val widthPx = (cardHeightPx * AspectRatios.WIDE).roundToInt().coerceAtLeast(1)
        return imageUrlOverride?.let {
            CardImagePreloadTarget(
                imageUrl = it,
                widthPx = widthPx,
                heightPx = cardHeightPx,
            )
        }
    }

    val playbackTicks = data.userData?.playbackPositionTicks ?: 0L
    val interceptedEpisode = type == BaseItemKind.EPISODE && playbackTicks > 0L
    val imageType =
        when {
            interceptedEpisode -> ImageType.PRIMARY
            type == BaseItemKind.EPISODE -> viewOptions.episodeImageType.imageType
            else -> viewOptions.imageType.imageType
        }
    val aspectRatio =
        when {
            interceptedEpisode -> 16f / 9f
            type == BaseItemKind.EPISODE -> viewOptions.episodeAspectRatio.ratio
            else -> viewOptions.aspectRatio.ratio
        }
    val widthPx = (cardHeightPx * aspectRatio).roundToInt().coerceAtLeast(1)
    val imageUrl =
        imageUrlOverride ?: imageUrlService.getItemImageUrl(
            item = this,
            imageType = imageType,
            fillHeight = cardHeightPx,
        )

    return imageUrl?.let {
        CardImagePreloadTarget(
            imageUrl = it,
            widthPx = widthPx,
            heightPx = cardHeightPx,
        )
    }
}

private class PageImagePreloader(
    context: Context,
) {
    private val appContext = context.applicationContext
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val queueMutex = Mutex()
    private val processedUrls = LinkedHashSet<String>()
    private val queuedTargets = LinkedHashMap<String, CardImagePreloadTarget>()
    private var currentUrl: String? = null
    private val wakeSignal = Channel<Unit>(capacity = Channel.CONFLATED)

    init {
        scope.launch {
            for (ignored in wakeSignal) {
                delay(PRELOAD_START_DELAY_MS)
                while (true) {
                    val target = dequeueNextTarget() ?: break
                    preload(target)
                    delay(PRELOAD_BETWEEN_IMAGES_DELAY_MS)
                }
            }
        }
    }

    fun submitRows(rows: List<List<CardImagePreloadTarget>>) {
        scope.launch {
            queueMutex.withLock {
                queuedTargets.clear()
                rows.forEach { row ->
                    row.forEach { target ->
                        if (target.imageUrl !in processedUrls && target.imageUrl != currentUrl) {
                            queuedTargets.putIfAbsent(target.imageUrl, target)
                        }
                    }
                }
            }
            wakeSignal.trySend(Unit)
        }
    }

    fun close() {
        wakeSignal.close()
        scope.cancel()
    }

    private suspend fun dequeueNextTarget(): CardImagePreloadTarget? =
        queueMutex.withLock {
            val entry = queuedTargets.entries.firstOrNull() ?: return null
            queuedTargets.remove(entry.key)
            currentUrl = entry.key
            entry.value
        }

    private suspend fun preload(target: CardImagePreloadTarget) {
        try {
            appContext.imageLoader.execute(
                buildCardImageRequest(
                    context = appContext,
                    imageUrl = target.imageUrl,
                    widthPx = target.widthPx,
                    heightPx = target.heightPx,
                ),
            )
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Timber.v(e, "Card preload failed for %s", target.imageUrl)
        } finally {
            queueMutex.withLock {
                processedUrls.add(target.imageUrl)
                while (processedUrls.size > MAX_TRACKED_PRELOAD_URLS) {
                    processedUrls.remove(processedUrls.first())
                }
                if (currentUrl == target.imageUrl) {
                    currentUrl = null
                }
            }
        }
    }
}
