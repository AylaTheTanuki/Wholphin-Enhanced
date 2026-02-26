package com.github.damontecres.wholphin.ui.cards

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.tv.material3.Card
import androidx.tv.material3.CardDefaults
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Text
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.ui.AspectRatios
import com.github.damontecres.wholphin.ui.components.ViewOptionImageType
import com.github.damontecres.wholphin.ui.enableMarquee
import kotlinx.coroutines.delay
import org.jellyfin.sdk.model.api.BaseItemKind // Added import for BaseItemKind

@Composable
fun GridCard(
    item: BaseItem?,
    onClick: () -> Unit,
    onLongClick: () -> Unit,
    modifier: Modifier = Modifier,
    interactionSource: MutableInteractionSource = remember { MutableInteractionSource() },
    imageAspectRatio: Float = AspectRatios.TALL,
    imageContentScale: ContentScale = ContentScale.Fit,
    imageType: ViewOptionImageType = ViewOptionImageType.PRIMARY,
    showTitle: Boolean = true,
) {
    val dto = item?.data
    val focused by interactionSource.collectIsFocusedAsState()

    // THE FINAL FIX: Smart Canvas Sizing
    // We check if this is our intercepted partially-watched episode
    val ticks = dto?.userData?.playbackPositionTicks ?: 0L
    val isInterceptedEpisode = item?.type == BaseItemKind.EPISODE && imageType == ViewOptionImageType.THUMB && ticks > 0L

    // If it is, force the canvas to be wide (16:9). Otherwise, obey the default rules.
    val finalAspectRatio = if (isInterceptedEpisode) (16f / 9f) else imageAspectRatio

    // PREMIUM TWEAK: Softer spacing and more dramatic elevation
    val spaceBetween by animateDpAsState(if (focused) 14.dp else 4.dp)
    val spaceBelow by animateDpAsState(if (focused) 6.dp else 12.dp)
    val scale by animateFloatAsState(if (focused) 1.12f else 1.0f)
    val elevation by animateDpAsState(if (focused) 20.dp else 0.dp)

    var focusedAfterDelay by remember { mutableStateOf(false) }

    val hideOverlayDelay = 500L
    if (focused) {
        LaunchedEffect(Unit) {
            delay(hideOverlayDelay)
            if (focused) focusedAfterDelay = true
        }
    } else {
        focusedAfterDelay = false
    }

    Column(
        verticalArrangement = Arrangement.spacedBy(spaceBetween),
        modifier = modifier.scale(scale),
    ) {
        Card(
            modifier =
                Modifier
                    .fillMaxWidth()
                    // THE PREMIUM FIX: Shadow instead of Border
                    .shadow(
                        elevation = elevation,
                        shape = RoundedCornerShape(10.dp),
                        ambientColor = Color.Black.copy(alpha = 0.5f),
                        spotColor = Color.White.copy(alpha = 0.2f) // Subtle "Frost" glow
                    ),
            onClick = onClick,
            onLongClick = onLongClick,
            interactionSource = interactionSource,
            colors = CardDefaults.colors(containerColor = Color.Transparent),
            scale = CardDefaults.scale(focusedScale = 1f),
        ) {
            ItemCardImage(
                item = item,
                imageType = imageType.imageType,
                name = item?.name,
                showOverlay = true,
                favorite = dto?.userData?.isFavorite ?: false,
                watched = dto?.userData?.played ?: false,
                unwatchedCount = dto?.userData?.unplayedItemCount ?: -1,
                watchedPercent = dto?.userData?.playedPercentage,
                numberOfVersions = dto?.mediaSourceCount ?: 0,
                useFallbackText = false,
                contentScale = imageContentScale,
                modifier =
                    Modifier
                        .fillMaxWidth()
                        // Use our dynamic smart aspect ratio here!
                        .aspectRatio(finalAspectRatio.coerceAtLeast(AspectRatios.MIN))
                        .background(MaterialTheme.colorScheme.surfaceVariant)
                        .clip(RoundedCornerShape(10.dp)),
            )
        }
        AnimatedVisibility(showTitle) {
            Column(
                verticalArrangement = Arrangement.spacedBy(2.dp),
                modifier = Modifier.padding(bottom = spaceBelow).fillMaxWidth(),
            ) {
                Text(
                    text = item?.title ?: "",
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodyLarge, // Slightly larger for premium feel
                    fontWeight = FontWeight.Bold,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).enableMarquee(focusedAfterDelay),
                )
                Text(
                    text = item?.subtitle ?: "",
                    maxLines = 1,
                    textAlign = TextAlign.Center,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f),
                    fontWeight = FontWeight.Normal,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp).enableMarquee(focusedAfterDelay),
                )
            }
        }
    }
}