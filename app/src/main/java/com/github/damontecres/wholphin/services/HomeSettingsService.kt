package com.github.damontecres.wholphin.services

import android.content.Context
import com.github.damontecres.wholphin.R
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.BaseItem
import com.github.damontecres.wholphin.data.model.HomePageSettings
import com.github.damontecres.wholphin.data.model.HomeRowConfig
import com.github.damontecres.wholphin.data.model.HomeRowViewOptions
import com.github.damontecres.wholphin.data.model.SUPPORTED_HOME_PAGE_SETTINGS_VERSION
import com.github.damontecres.wholphin.preferences.HomePagePreferences
import com.github.damontecres.wholphin.preferences.PrefContentScale
import com.github.damontecres.wholphin.ui.AspectRatio
import com.github.damontecres.wholphin.ui.Cards
import com.github.damontecres.wholphin.ui.DefaultItemFields
import com.github.damontecres.wholphin.ui.SlimItemFields
import com.github.damontecres.wholphin.ui.components.ViewOptionImageType
import com.github.damontecres.wholphin.ui.components.getGenreImageMap
import com.github.damontecres.wholphin.ui.main.settings.Library
import com.github.damontecres.wholphin.ui.main.settings.favoriteOptions
import com.github.damontecres.wholphin.ui.toBaseItems
import com.github.damontecres.wholphin.ui.toServerString
import com.github.damontecres.wholphin.util.GetGenresRequestHandler
import com.github.damontecres.wholphin.util.GetItemsRequestHandler
import com.github.damontecres.wholphin.util.GetPersonsHandler
import com.github.damontecres.wholphin.util.HomeRowLoadingState
import com.github.damontecres.wholphin.util.HomeRowLoadingState.Success
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToStream
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.jellyfin.sdk.api.client.ApiClient
import org.jellyfin.sdk.api.client.extensions.displayPreferencesApi
import org.jellyfin.sdk.api.client.extensions.liveTvApi
import org.jellyfin.sdk.api.client.extensions.userLibraryApi
import org.jellyfin.sdk.model.UUID
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.api.CollectionType
import org.jellyfin.sdk.model.api.ImageType
import org.jellyfin.sdk.model.api.ItemSortBy
import org.jellyfin.sdk.model.api.SortOrder
import org.jellyfin.sdk.model.api.UserDto
import org.jellyfin.sdk.model.api.request.GetGenresRequest
import org.jellyfin.sdk.model.api.request.GetItemsRequest
import org.jellyfin.sdk.model.api.request.GetLatestMediaRequest
import org.jellyfin.sdk.model.api.request.GetPersonsRequest
import org.jellyfin.sdk.model.api.request.GetRecommendedProgramsRequest
import org.jellyfin.sdk.model.api.request.GetRecordingsRequest
import timber.log.Timber
import java.io.File
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class HomeSettingsService @Inject constructor(
    @param:ApplicationContext private val context: Context,
    private val api: ApiClient,
    private val serverRepository: ServerRepository,
    private val userPreferencesService: UserPreferencesService,
    private val navDrawerService: NavDrawerService,
    private val latestNextUpService: LatestNextUpService,
    private val imageUrlService: ImageUrlService,
) {
    @OptIn(ExperimentalSerializationApi::class)
    val jsonParser = Json {
        isLenient = true
        ignoreUnknownKeys = true
        allowTrailingComma = true
    }

    val currentSettings = MutableStateFlow(HomePageResolvedSettings.EMPTY)

    private val continueWatchingOptions = HomeRowViewOptions(
        heightDp = Cards.HEIGHT_EPISODE,
        aspectRatio = AspectRatio.WIDE,
        imageType = ViewOptionImageType.THUMB,
        contentScale = PrefContentScale.CROP,
        useSeries = false
    )

    private val nextUpOptions = HomeRowViewOptions(
        heightDp = Cards.HEIGHT_EPISODE,
        aspectRatio = AspectRatio.WIDE,
        imageType = ViewOptionImageType.THUMB,
        contentScale = PrefContentScale.FIT
    )

    suspend fun parseFromWebConfig(userId: UUID): HomePageSettings? {
        return try {
            api.displayPreferencesApi.getDisplayPreferences("Home", userId, context.getString(R.string.app_name))
            null
        } catch (_: Exception) {
            null
        }
    }

    suspend fun saveToServer(userId: UUID, settings: HomePageSettings, displayPreferencesId: String = DISPLAY_PREF_ID) {
        val current = api.displayPreferencesApi.getDisplayPreferences(displayPreferencesId, userId, context.getString(R.string.app_name)).content
        val customPrefs = current.customPrefs.toMutableMap()
        customPrefs[CUSTOM_PREF_ID] = jsonParser.encodeToString(HomePageSettings.serializer(), settings)

        api.displayPreferencesApi.updateDisplayPreferences(
            displayPreferencesId = displayPreferencesId,
            userId = userId,
            client = context.getString(R.string.app_name),
            data = current.copy(customPrefs = customPrefs),
        )
    }

    suspend fun loadFromServer(userId: UUID, displayPreferencesId: String = DISPLAY_PREF_ID): HomePageSettings? {
        val current = api.displayPreferencesApi.getDisplayPreferences(displayPreferencesId, userId, context.getString(R.string.app_name)).content
        return current.customPrefs[CUSTOM_PREF_ID]?.let { decode(jsonParser.parseToJsonElement(it)) }
    }

    private fun filename(userId: UUID) = "${CUSTOM_PREF_ID}_${userId.toServerString()}.json"

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun saveToLocal(userId: UUID, settings: HomePageSettings) {
        val dir = File(context.filesDir, CUSTOM_PREF_ID).apply { mkdirs() }
        File(dir, filename(userId)).outputStream().use { jsonParser.encodeToStream(HomePageSettings.serializer(), settings, it) }
    }

    @OptIn(ExperimentalSerializationApi::class)
    suspend fun loadFromLocal(userId: UUID): HomePageSettings? {
        val file = File(File(context.filesDir, CUSTOM_PREF_ID), filename(userId))
        return if (file.exists()) decode(jsonParser.parseToJsonElement(file.readText())) else null
    }

    fun decode(element: JsonElement): HomePageSettings {
        val version = element.jsonObject["version"]?.jsonPrimitive?.intOrNull ?: 1
        val rows = element.jsonObject["rows"]?.jsonArray?.mapNotNull {
            try { jsonParser.decodeFromJsonElement<HomeRowConfig>(it) } catch (_: Exception) { null }
        }.orEmpty()
        return HomePageSettings(rows, version)
    }

    suspend fun loadCurrentSettings(userId: UUID) {
        val settings = try { loadFromLocal(userId) } catch (_: Exception) { null } ?: try { loadFromServer(userId) } catch (_: Exception) { null }
        val resolvedRows = (settings?.rows ?: createDefault(userId).rows.map { it.config }).mapIndexed { index, config -> resolve(index, config) }
        currentSettings.update { HomePageResolvedSettings(resolvedRows) }
    }

    suspend fun updateCurrent(settings: HomePageSettings) {
        currentSettings.update { HomePageResolvedSettings(settings.rows.mapIndexed { index, config -> resolve(index, config) }) }
    }

    suspend fun createDefault(userId: UUID): HomePageResolvedSettings {
        val user = serverRepository.currentUser.value?.takeIf { it.id == userId }
        val userDto = serverRepository.currentUserDto.value?.takeIf { it.id == userId }
        val libraries = if (user != null) navDrawerService.getFilteredUserLibraries(user, userDto?.tvAccess ?: false) else navDrawerService.getAllUserLibraries(userId, userDto?.tvAccess ?: false)
        val prefs = userPreferencesService.getCurrent().appPreferences.homePagePreferences

        val includedIds = libraries.mapIndexed { index, library ->
            if (library.collectionType == CollectionType.LIVETV) HomeRowConfigDisplay(index, context.getString(R.string.live_tv), HomeRowConfig.TvPrograms())
            else HomeRowConfigDisplay(index, getRecentlyAddedTitle(context, library), HomeRowConfig.RecentlyAdded(library.itemId))
        }
        val continueRows = if (prefs.combineContinueNext) listOf(HomeRowConfigDisplay(includedIds.size + 1, context.getString(R.string.combine_continue_next), HomeRowConfig.ContinueWatchingCombined(viewOptions = continueWatchingOptions)))
        else listOf(
            HomeRowConfigDisplay(includedIds.size + 1, context.getString(R.string.continue_watching), HomeRowConfig.ContinueWatching(viewOptions = continueWatchingOptions)),
            HomeRowConfigDisplay(includedIds.size + 2, context.getString(R.string.next_up), HomeRowConfig.NextUp(viewOptions = nextUpOptions))
        )

        return HomePageResolvedSettings(continueRows + HomeRowConfigDisplay(includedIds.size + 3, context.getString(R.string.favorites), HomeRowConfig.MyList()) + includedIds)
    }

    suspend fun resolve(id: Int, config: HomeRowConfig): HomeRowConfigDisplay = when (config) {
        is HomeRowConfig.ByParent -> HomeRowConfigDisplay(id, api.userLibraryApi.getItem(itemId = config.parentId).content.name ?: "", config)
        is HomeRowConfig.ContinueWatching -> HomeRowConfigDisplay(id, context.getString(R.string.continue_watching), config.copy(viewOptions = continueWatchingOptions))
        is HomeRowConfig.ContinueWatchingCombined -> HomeRowConfigDisplay(id, context.getString(R.string.combine_continue_next), config.copy(viewOptions = continueWatchingOptions))
        is HomeRowConfig.NextUp -> HomeRowConfigDisplay(id, context.getString(R.string.next_up), config.copy(viewOptions = nextUpOptions))
        is HomeRowConfig.Genres -> HomeRowConfigDisplay(id, context.getString(R.string.genres_in, api.userLibraryApi.getItem(itemId = config.parentId).content.name ?: ""), config)
        is HomeRowConfig.GetItems -> HomeRowConfigDisplay(id, config.name, config)
        is HomeRowConfig.RecentlyAdded -> HomeRowConfigDisplay(id, context.getString(R.string.recently_added_in, api.userLibraryApi.getItem(itemId = config.parentId).content.name ?: ""), config)
        is HomeRowConfig.RecentlyReleased -> HomeRowConfigDisplay(id, context.getString(R.string.recently_released_in, api.userLibraryApi.getItem(itemId = config.parentId).content.name ?: ""), config)
        is HomeRowConfig.Favorite -> HomeRowConfigDisplay(id, context.getString(R.string.favorite_items, context.getString(favoriteOptions[config.kind]!!)), config)
        is HomeRowConfig.MyList -> HomeRowConfigDisplay(id, context.getString(R.string.favorites), config)
        is HomeRowConfig.Recordings -> HomeRowConfigDisplay(id, context.getString(R.string.active_recordings), config)
        is HomeRowConfig.TvPrograms -> HomeRowConfigDisplay(id, context.getString(R.string.live_tv), config)
        is HomeRowConfig.TvChannels -> HomeRowConfigDisplay(id, context.getString(R.string.channels), config)
        is HomeRowConfig.Suggestions -> HomeRowConfigDisplay(id, context.getString(R.string.suggestions_for, api.userLibraryApi.getItem(itemId = config.parentId).content.name ?: ""), config)
    }

    suspend fun fetchDataForRow(row: HomeRowConfig, scope: CoroutineScope, prefs: HomePagePreferences, userDto: UserDto, libraries: List<Library>, limit: Int = prefs.maxItemsPerRow): HomeRowLoadingState = when (row) {
        is HomeRowConfig.ContinueWatching -> Success(context.getString(R.string.continue_watching), latestNextUpService.getResume(userDto.id, limit, true, row.viewOptions.useSeries), row.viewOptions)
        is HomeRowConfig.NextUp -> Success(context.getString(R.string.next_up), latestNextUpService.getNextUp(userDto.id, limit, prefs.enableRewatchingNextUp, false, prefs.maxDaysNextUp, row.viewOptions.useSeries), row.viewOptions)
        is HomeRowConfig.ContinueWatchingCombined -> Success(context.getString(R.string.continue_watching), latestNextUpService.buildCombined(latestNextUpService.getResume(userDto.id, limit, true, row.viewOptions.useSeries), latestNextUpService.getNextUp(userDto.id, limit, prefs.enableRewatchingNextUp, false, prefs.maxDaysNextUp, row.viewOptions.useSeries)), row.viewOptions)
        is HomeRowConfig.Genres -> {
            val items = GetGenresRequestHandler.execute(api, GetGenresRequest(parentId = row.parentId, userId = userDto.id, limit = limit)).content.items
            val title = libraries.firstOrNull { it.itemId == row.parentId }?.name?.let { context.getString(R.string.genres_in, it) } ?: context.getString(R.string.genres)
            Success(title, items.map { BaseItem(it, false, getGenreImageMap(api, scope, imageUrlService, items.map { it.id }, row.parentId, null, null)[it.id]) }, row.viewOptions)
        }
        is HomeRowConfig.RecentlyAdded -> Success(getRecentlyAddedTitle(context, libraries.firstOrNull { it.itemId == row.parentId }), api.userLibraryApi.getLatestMedia(GetLatestMediaRequest(fields = SlimItemFields, imageTypeLimit = 1, parentId = row.parentId, groupItems = true, limit = limit, isPlayed = null)).content.map { BaseItem.from(it, api, row.viewOptions.useSeries) }, row.viewOptions)
        is HomeRowConfig.RecentlyReleased -> Success(libraries.firstOrNull { it.itemId == row.parentId }?.name?.let { context.getString(R.string.recently_released_in, it) } ?: context.getString(R.string.recently_released), GetItemsRequestHandler.execute(api, GetItemsRequest(parentId = row.parentId, limit = limit, sortBy = listOf(ItemSortBy.PREMIERE_DATE), sortOrder = listOf(SortOrder.DESCENDING), fields = DefaultItemFields, recursive = true)).content.items.map { BaseItem.from(it, api, row.viewOptions.useSeries) }, row.viewOptions)
        is HomeRowConfig.ByParent -> Success(api.userLibraryApi.getItem(itemId = row.parentId).content.name ?: context.getString(R.string.collection), GetItemsRequestHandler.execute(api, GetItemsRequest(userId = userDto.id, parentId = row.parentId, recursive = row.recursive, sortBy = row.sort?.let { listOf(it.sort) }, sortOrder = row.sort?.let { listOf(it.direction) }, limit = limit, fields = DefaultItemFields)).content.items.map { BaseItem(it, row.viewOptions.useSeries) }, row.viewOptions)
        is HomeRowConfig.GetItems -> Success(row.name, GetItemsRequestHandler.execute(api, row.getItems.let { if (it.limit == null) it.copy(userId = userDto.id, limit = limit) else it.copy(userId = userDto.id) }).content.items.map { BaseItem(it, row.viewOptions.useSeries) }, row.viewOptions)
        is HomeRowConfig.Favorite -> {
            // THE CRITICAL FIX: Changed config.kind to row.kind
            val title = context.getString(R.string.favorite_items, context.getString(favoriteOptions[row.kind]!!))
            val items = if (row.kind == BaseItemKind.PERSON) GetPersonsHandler.execute(api, GetPersonsRequest(userId = userDto.id, limit = limit, fields = DefaultItemFields, isFavorite = true, enableImages = true, enableImageTypes = listOf(ImageType.PRIMARY))).content.items.map { BaseItem(it, true) }
            else GetItemsRequestHandler.execute(api, GetItemsRequest(userId = userDto.id, recursive = true, limit = limit, fields = DefaultItemFields, includeItemTypes = listOf(row.kind), isFavorite = true)).content.items.map { BaseItem(it, row.viewOptions.useSeries) }
            Success(title, items, row.viewOptions)
        }
        is HomeRowConfig.MyList -> {
            val movies = GetItemsRequestHandler.execute(api, GetItemsRequest(userId = userDto.id, recursive = true, fields = DefaultItemFields, includeItemTypes = listOf(BaseItemKind.MOVIE), isFavorite = true)).content.items
            val shows = GetItemsRequestHandler.execute(api, GetItemsRequest(userId = userDto.id, recursive = true, fields = DefaultItemFields, includeItemTypes = listOf(BaseItemKind.SERIES), isFavorite = true)).content.items
            Success(context.getString(R.string.favorites), (movies + shows).sortedByDescending { it.dateCreated }.map { BaseItem(it, row.viewOptions.useSeries) }, row.viewOptions)
        }
        is HomeRowConfig.Recordings -> Success(context.getString(R.string.active_recordings), api.liveTvApi.getRecordings(GetRecordingsRequest(userId = userDto.id, isInProgress = true, fields = DefaultItemFields, limit = limit, enableImages = true, enableUserData = true)).content.items.map { BaseItem(it, row.viewOptions.useSeries) }, row.viewOptions)
        is HomeRowConfig.TvPrograms -> Success(context.getString(R.string.live_tv), api.liveTvApi.getRecommendedPrograms(GetRecommendedProgramsRequest(userId = userDto.id, fields = DefaultItemFields, limit = limit, enableUserData = true, enableImages = true, enableImageTypes = listOf(ImageType.PRIMARY), imageTypeLimit = 1)).content.items.map { BaseItem(it, row.viewOptions.useSeries) }, row.viewOptions)
        is HomeRowConfig.TvChannels -> Success(context.getString(R.string.channels), api.liveTvApi.getLiveTvChannels(userId = userDto.id, fields = DefaultItemFields, limit = limit, enableImages = true).toBaseItems(api, row.viewOptions.useSeries), row.viewOptions)
        is HomeRowConfig.Suggestions -> Success(context.getString(R.string.suggestions_for, api.userLibraryApi.getItem(itemId = row.parentId).content.name ?: ""), emptyList(), row.viewOptions)
    }

    companion object {
        const val DISPLAY_PREF_ID = "default"
        const val CUSTOM_PREF_ID = "home_settings"
    }
}

data class HomeRowConfigDisplay(val id: Int, val title: String, val config: HomeRowConfig)
data class HomePageResolvedSettings(val rows: List<HomeRowConfigDisplay>) {
    companion object { val EMPTY = HomePageResolvedSettings(listOf()) }
}
enum class HomeSectionType(val serialName: String) {
    NONE("none"), SMALL_LIBRARY_TILES("smalllibrarytitles"), LIBRARY_BUTTONS("librarybuttons"), ACTIVE_RECORDINGS("activerecordings"), RESUME("resume"), RESUME_AUDIO("resumeaudio"), LATEST_MEDIA("latestmedia"), NEXT_UP("nextup"), LIVE_TV("livetv"), RESUME_BOOK("resumebook");
    companion object { fun fromString(homeKey: String?) = homeKey?.let { key -> entries.firstOrNull { it.serialName == key } } }
}
class UnsupportedHomeSettingsVersionException(val unsupportedVersion: Int?, val maxSupportedVersion: Int = SUPPORTED_HOME_PAGE_SETTINGS_VERSION) : Exception("Unsupported version $unsupportedVersion, max supported is $maxSupportedVersion")
fun getRecentlyAddedTitle(context: Context, library: Library?): String = if (library?.isRecordingFolder == true) context.getString(R.string.recently_recorded) else library?.name?.let { context.getString(R.string.recently_added_in, it) } ?: context.getString(R.string.recently_added)