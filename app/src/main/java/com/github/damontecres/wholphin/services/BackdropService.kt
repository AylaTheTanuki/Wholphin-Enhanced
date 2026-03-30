package com.github.damontecres.wholphin.services

import android.content.Context
import android.graphics.Bitmap
import android.util.LruCache
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.isSpecified
import androidx.core.graphics.drawable.toBitmap
import androidx.datastore.core.DataStore
import androidx.palette.graphics.Palette
import coil3.asDrawable
import coil3.imageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.request.bitmapConfig
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.DiscoverItem
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.BackdropStyle
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.ImageType
import timber.log.Timber
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class BackdropService
    @Inject
    constructor(
        @param:ApplicationContext private val context: Context,
        private val imageUrlService: ImageUrlService,
        private val preferences: DataStore<AppPreferences>,
    ) {
        private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        private val requestMutex = Mutex()
        private val extractedColorCache = LruCache<String, ExtractedColors>(50)
        private var pendingRequest: BackdropRequest? = null
        private var extractionJob: Job? = null

        private val _backdropFlow = MutableStateFlow<BackdropResult>(BackdropResult.NONE)
        val backdropFlow = _backdropFlow

        suspend fun submit(item: BaseItem) =
            withContext(Dispatchers.IO) {
                val imageUrl =
                    if (item.type == BaseItemKind.GENRE) {
                        item.imageUrlOverride
                    } else {
                        imageUrlService.getItemImageUrl(item, ImageType.BACKDROP)
                    }
                submit(item.id.toString(), imageUrl)
            }

        suspend fun submit(item: DiscoverItem) = submit("discover_${item.id}", item.backDropUrl)

        suspend fun submit(
            itemId: String,
            imageUrl: String?,
        ) = withContext(Dispatchers.IO) {
            val request = BackdropRequest(itemId, imageUrl)
            requestMutex.withLock {
                val displayedBackdrop = _backdropFlow.value
                if (pendingRequest == request ||
                    (displayedBackdrop.itemId == itemId && displayedBackdrop.imageUrl == imageUrl)
                ) {
                    return@withLock
                }

                pendingRequest = request
                extractionJob?.cancel()

                if (request.imageUrl.isNullOrBlank()) {
                    extractionJob = null
                    _backdropFlow.value =
                        BackdropResult(
                            itemId = request.itemId,
                            imageUrl = null,
                        )
                    return@withLock
                }

                extractionJob =
                    scope.launch {
                        applyBackdropRequest(request)
                    }
            }
        }

        suspend fun clearBackdrop() {
            requestMutex.withLock {
                pendingRequest = null
                extractionJob?.cancel()
                extractionJob = null
                _backdropFlow.value = BackdropResult.NONE
            }
        }

        suspend fun hideBackdropImageKeepColors() {
            requestMutex.withLock {
                pendingRequest = null
                extractionJob?.cancel()
                extractionJob = null

                val currentBackdrop = _backdropFlow.value
                if (currentBackdrop.imageUrl != null) {
                    _backdropFlow.value = currentBackdrop.copy(imageUrl = null)
                }
            }
        }

        private suspend fun applyBackdropRequest(request: BackdropRequest) {
            val backdropStyle =
                preferences.data
                    .firstOrNull()
                    ?.interfacePreferences
                    ?.backdropStyle
            val dynamicEnabled =
                backdropStyle == BackdropStyle.BACKDROP_DYNAMIC_COLOR ||
                    backdropStyle == BackdropStyle.UNRECOGNIZED
            val (primaryColor, secondaryColor, tertiaryColor) =
                if (dynamicEnabled) {
                    extractColorsFromBackdrop(request.imageUrl)
                } else {
                    ExtractedColors.DEFAULT
                }
            requestMutex.withLock {
                if (pendingRequest == request) {
                    _backdropFlow.value =
                        BackdropResult(
                            itemId = request.itemId,
                            imageUrl = request.imageUrl,
                            primaryColor = primaryColor,
                            secondaryColor = secondaryColor,
                            tertiaryColor = tertiaryColor,
                        )
                    extractionJob = null
                }
            }
        }

        private suspend fun extractColorsFromBackdrop(imageUrl: String?): ExtractedColors =
            withContext(Dispatchers.IO) {
                if (imageUrl.isNullOrBlank()) {
                    return@withContext ExtractedColors.DEFAULT
                }
                extractedColorCache.get(imageUrl)?.let {
                    return@withContext it
                }

                try {
                    val loader = context.imageLoader
                    val request =
                        ImageRequest
                            .Builder(context)
                            .data(imageUrl)
                            .size(BACKDROP_SAMPLE_WIDTH_PX, BACKDROP_SAMPLE_HEIGHT_PX)
                            .allowHardware(false)
                            .bitmapConfig(Bitmap.Config.ARGB_8888)
                            .build()

                    val result = loader.execute(request)
                    if (result is SuccessResult) {
                        val drawable = result.image.asDrawable(context.resources)
                        val bitmap = drawable.toBitmap(config = Bitmap.Config.ARGB_8888)
                        extractColorsFromBitmap(bitmap).also {
                            extractedColorCache.put(imageUrl, it)
                        }
                    } else {
                        ExtractedColors.DEFAULT
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Timber.e(e, "Error extracting colors from URL: $imageUrl")
                    ExtractedColors.DEFAULT
                }
            }

        /**
         * Helper function to determine if a color is "cool" (blue/purple/green) vs "warm" (red/orange/yellow)
         *
         * Cool colors have more blue/green than red
         */
        private val Palette.Swatch.coolColor: Boolean
            get() {
                val r = (rgb shr 16) and 0xFF
                val g = (rgb shr 8) and 0xFF
                val b = rgb and 0xFF
                return b > r && (b + g) > (r * 1.5f)
            }

        private fun toColor(
            swatch: Palette.Swatch?,
            alpha: Float,
        ): Color = swatch?.rgb?.let(::Color)?.copy(alpha = alpha) ?: Color.Transparent

        /**
         * Extracts colors from a bitmap using Android's Palette API.
         *
         * - Primary (Bottom-Right): darkVibrant -> darkMuted -> default
         * - Secondary (Top-Left): Smart selection based on color temperature (prefers cool colors)
         * - Tertiary (Top-Right): vibrant -> lightVibrant -> default
         *
         * @param bitmap The bitmap to extract colors from
         * @return ExtractedColors containing primary, secondary, and tertiary colors
         */
        private suspend fun extractColorsFromBitmap(bitmap: Bitmap): ExtractedColors =
            try {
                val palette = Palette.from(bitmap).generate()

                val vibrant = palette.vibrantSwatch
                val darkVibrant = palette.darkVibrantSwatch
                val lightVibrant = palette.lightVibrantSwatch
                val muted = palette.mutedSwatch
                val darkMuted = palette.darkMutedSwatch
                val lightMuted = palette.lightMutedSwatch
                val dominant = palette.dominantSwatch

                // Primary (Bottom-Right)
                val primaryColor = toColor(darkVibrant ?: darkMuted, .4f)

                // Secondary (Top-Left): Smart selection based on color properties
                // If Vibrant is cool (blue/purple), use it. If Vibrant is warm (yellow/orange) and Muted is cool, use Muted.
                // This ensures we get cool tones (blue/purple) for top-left when available
                val secondaryColor =
                    when {
                        vibrant != null && vibrant.coolColor -> vibrant
                        muted != null && muted.coolColor -> muted
                        vibrant != null -> vibrant
                        muted != null -> muted
                        else -> null
                    }.let { toColor(it, .4f) }

                // Tertiary (Top-Right under image)
                val tertiaryColor = toColor(vibrant ?: lightVibrant, .35f)

                Timber.v(
                    "Colors extracted: primary=%s, secondary=%s, tertiary=%s",
                    primaryColor,
                    secondaryColor,
                    tertiaryColor,
                )
                ExtractedColors(primaryColor, secondaryColor, tertiaryColor)
            } catch (e: Exception) {
                Timber.e(e, "Error extracting palette colors")
                ExtractedColors.DEFAULT
            }

        fun clearColorCache() {
            extractedColorCache.evictAll()
        }
    }

private data class BackdropRequest(
    val itemId: String,
    val imageUrl: String?,
)

private const val BACKDROP_SAMPLE_WIDTH_PX = 256
private const val BACKDROP_SAMPLE_HEIGHT_PX = 144

data class BackdropResult(
    val itemId: String?,
    val imageUrl: String?,
    val primaryColor: Color = Color.Unspecified,
    val secondaryColor: Color = Color.Unspecified,
    val tertiaryColor: Color = Color.Unspecified,
) {
    val hasColors: Boolean =
        primaryColor.isSpecified ||
            secondaryColor.isSpecified ||
            tertiaryColor.isSpecified

    companion object {
        val NONE =
            BackdropResult(
                null,
                null,
            )
    }
}

data class ExtractedColors(
    val primary: Color,
    val secondary: Color,
    val tertiary: Color,
) {
    companion object {
        val DEFAULT = ExtractedColors(Color.Unspecified, Color.Unspecified, Color.Unspecified)
    }
}
