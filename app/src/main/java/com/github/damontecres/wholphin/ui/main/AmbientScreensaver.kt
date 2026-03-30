package com.github.damontecres.wholphin.ui.main

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.lifecycle.viewmodel.compose.hiltViewModel
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import coil3.compose.AsyncImage
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.services.ImageUrlService
import com.github.damontecres.wholphin.ui.DefaultItemFields
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.util.GetItemsRequestHandler
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.isActive
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import javax.inject.Inject

@HiltViewModel
class AmbientScreensaverViewModel
@Inject
constructor(
    private val api: ApiClient,
    private val serverRepository: ServerRepository,
    private val imageUrlService: ImageUrlService,
) : ViewModel() {
    private val _backdrops = MutableStateFlow<List<String>>(emptyList())
    val backdrops: StateFlow<List<String>> = _backdrops

    init {
        loadBackdrops()
    }

    private fun loadBackdrops() {
        viewModelScope.launchIO {
            val userId = serverRepository.currentUser.value?.id ?: return@launchIO
            val request = GetItemsRequest(
                userId = userId,
                recursive = true,
                includeItemTypes = listOf(BaseItemKind.MOVIE, BaseItemKind.SERIES),
                sortBy = listOf(ItemSortBy.RANDOM),
                sortOrder = listOf(SortOrder.DESCENDING),
                limit = 50,
                fields = DefaultItemFields,
                enableImages = true,
                enableImageTypes = listOf(ImageType.BACKDROP)
            )
            val items = GetItemsRequestHandler.execute(api, request).content.items
            val urls = items.mapNotNull {
                imageUrlService.getItemImageUrl(it.id, ImageType.BACKDROP)
            }
            _backdrops.value = urls
        }
    }
}

@Composable
fun AmbientScreensaver(
    viewModel: AmbientScreensaverViewModel = hiltViewModel()
) {
    val backdrops by viewModel.backdrops.collectAsState()
    var currentImageIndex by remember { mutableIntStateOf(0) }

    val focusRequester = remember { FocusRequester() }
    LaunchedEffect(Unit) { focusRequester.requestFocus() }

    LaunchedEffect(backdrops) {
        if (backdrops.isNotEmpty()) {
            while (isActive) {
                delay(30000)
                currentImageIndex = (currentImageIndex + 1) % backdrops.size
            }
        }
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
            .focusRequester(focusRequester)
            .focusable()
    ) {
        if (backdrops.isNotEmpty()) {
            val infiniteTransition = rememberInfiniteTransition(label = "ken_burns")
            val scale by infiniteTransition.animateFloat(
                initialValue = 1.0f,
                targetValue = 1.2f,
                animationSpec = infiniteRepeatable(
                    animation = tween(30000, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "scale"
            )

            Crossfade(
                targetState = currentImageIndex,
                animationSpec = tween(2000),
                label = "backdrop_crossfade"
            ) { index ->
                AsyncImage(
                    model = backdrops[index],
                    contentDescription = null,
                    modifier = Modifier
                        .fillMaxSize()
                        .scale(scale),
                    contentScale = ContentScale.Crop
                )
            }
        }

        // Dark gradient along the bottom edge so the clock always reads cleanly
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color.Transparent,
                            Color.Black.copy(alpha = 0.55f)
                        )
                    )
                )
        )

        // Clock — bottom right, no background box, just a strong text shadow
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(end = 48.dp, bottom = 36.dp),
            contentAlignment = Alignment.BottomEnd
        ) {
            var timeText by remember { mutableStateOf("") }

            LaunchedEffect(Unit) {
                while (isActive) {
                    timeText = java.time.LocalTime.now()
                        .format(java.time.format.DateTimeFormatter.ofPattern("h:mm a"))
                    delay(1000)
                }
            }

            androidx.tv.material3.Text(
                text = timeText,
                color = Color.White,
                fontSize = 48.sp,
                fontWeight = FontWeight.Light,
                style = TextStyle(
                    shadow = Shadow(
                        color = Color.Black.copy(alpha = 0.9f),
                        offset = Offset(0f, 2f),
                        blurRadius = 12f
                    )
                )
            )
        }
    }
}