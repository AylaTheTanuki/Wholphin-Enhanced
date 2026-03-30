package com.github.damontecres.wholphin

import android.content.Intent
import android.content.res.Configuration
import android.os.Bundle
import android.view.WindowManager
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.RectangleShape
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.unit.dp
import androidx.datastore.core.DataStore
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.LifecycleEventEffect
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.navigation3.rememberViewModelStoreNavEntryDecorator
import androidx.navigation3.runtime.NavEntry
import androidx.navigation3.runtime.rememberSaveableStateHolderNavEntryDecorator
import androidx.navigation3.ui.NavDisplay
import androidx.tv.material3.ExperimentalTvMaterial3Api
import androidx.tv.material3.MaterialTheme
import androidx.tv.material3.Surface
import com.github.damontecres.wholphin.data.ServerRepository
import com.github.damontecres.wholphin.data.model.JellyfinServer
import com.github.damontecres.wholphin.preferences.AppPreference
import com.github.damontecres.wholphin.preferences.AppPreferences
import com.github.damontecres.wholphin.preferences.UserPreferences
import com.github.damontecres.wholphin.services.AppUpgradeHandler
import com.github.damontecres.wholphin.services.BackdropService
import com.github.damontecres.wholphin.services.DatePlayedInvalidationService
import com.github.damontecres.wholphin.services.DeviceProfileService
import com.github.damontecres.wholphin.services.ImageUrlService
import com.github.damontecres.wholphin.services.NavigationManager
import com.github.damontecres.wholphin.services.PlaybackLifecycleObserver
import com.github.damontecres.wholphin.services.RefreshRateService
import com.github.damontecres.wholphin.services.ServerEventListener
import com.github.damontecres.wholphin.services.SetupDestination
import com.github.damontecres.wholphin.services.SetupNavigationManager
import com.github.damontecres.wholphin.services.SuggestionsSchedulerService
import com.github.damontecres.wholphin.services.UpdateChecker
import com.github.damontecres.wholphin.services.UserSwitchListener
import com.github.damontecres.wholphin.services.hilt.AuthOkHttpClient
import com.github.damontecres.wholphin.services.tvprovider.TvProviderSchedulerService
import com.github.damontecres.wholphin.ui.CoilConfig
import com.github.damontecres.wholphin.ui.LocalImageUrlService
import com.github.damontecres.wholphin.ui.components.LoadingPage
import com.github.damontecres.wholphin.ui.detail.series.SeasonEpisodeIds
import com.github.damontecres.wholphin.ui.launchIO
import com.github.damontecres.wholphin.ui.nav.ApplicationContent
import com.github.damontecres.wholphin.ui.nav.Destination
import com.github.damontecres.wholphin.ui.setup.SwitchServerContent
import com.github.damontecres.wholphin.ui.setup.SwitchUserContent
import com.github.damontecres.wholphin.ui.theme.WholphinTheme
import com.github.damontecres.wholphin.ui.util.ProvideLocalClock
import com.github.damontecres.wholphin.util.DebugLogTree
import com.github.damontecres.wholphin.util.ExceptionHandler
import dagger.hilt.android.AndroidEntryPoint
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import org.jellyfin.sdk.api.client.exception.InvalidStatusException
import org.jellyfin.sdk.api.client.extensions.systemApi
import org.jellyfin.sdk.model.api.BaseItemKind
import org.jellyfin.sdk.model.serializer.toUUIDOrNull
import timber.log.Timber
import java.util.UUID
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : AppCompatActivity() {
    private val viewModel: MainActivityViewModel by viewModels()

    @Inject
    lateinit var userPreferencesDataStore: DataStore<AppPreferences>

    @AuthOkHttpClient
    @Inject
    lateinit var okHttpClient: OkHttpClient

    @Inject
    lateinit var navigationManager: NavigationManager

    @Inject
    lateinit var setupNavigationManager: SetupNavigationManager

    @Inject
    lateinit var updateChecker: UpdateChecker

    @Inject
    lateinit var appUpgradeHandler: AppUpgradeHandler

    @Inject
    lateinit var playbackLifecycleObserver: PlaybackLifecycleObserver

    @Inject
    lateinit var imageUrlService: ImageUrlService

    @Inject
    lateinit var refreshRateService: RefreshRateService

    @Inject
    lateinit var userSwitchListener: UserSwitchListener

    @Inject
    lateinit var tvProviderSchedulerService: TvProviderSchedulerService

    @Inject
    lateinit var suggestionsSchedulerService: SuggestionsSchedulerService

    @Inject
    lateinit var serverEventListener: ServerEventListener

    @Inject
    lateinit var datePlayedInvalidationService: DatePlayedInvalidationService

    private var signInAuto = true

    private var forceInteractionReset: (() -> Unit)? = null

    private var justCreated = false

    private var lastAmbientDismissAt = 0L

    @OptIn(ExperimentalTvMaterial3Api::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        instance = this
        Timber.d("BREADCRUMB: MAIN - onCreate triggered")
        lifecycle.addObserver(playbackLifecycleObserver)
        if (savedInstanceState == null) {
            lifecycleScope.launchIO {
                appUpgradeHandler.copySubfont(false)
            }
        }
        viewModel.serverRepository.currentUser.observe(this) { user ->
            if (user?.hasPin == true) {
                window?.setFlags(
                    WindowManager.LayoutParams.FLAG_SECURE,
                    WindowManager.LayoutParams.FLAG_SECURE,
                )
            } else {
                window?.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
            }
        }
        justCreated = true
        viewModel.appStart()
        setContent {
            val appPreferences by userPreferencesDataStore.data.collectAsState(null)
            if (appPreferences == null) {
                var showLoading by remember { mutableStateOf(false) }
                LaunchedEffect(Unit) {
                    delay(500)
                    Timber.i("Showing loading page")
                    showLoading = true
                }
                if (showLoading) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color.Black),
                    ) {
                        LoadingPage()
                    }
                }
            }
            appPreferences?.let { appPreferences ->
                LaunchedEffect(appPreferences.signInAutomatically) {
                    signInAuto = appPreferences.signInAutomatically
                }
                CoilConfig(
                    diskCacheSizeBytes =
                        appPreferences.advancedPreferences.imageDiskCacheSizeBytes.let {
                            if (it < AppPreference.ImageDiskCacheSize.min * AppPreference.MEGA_BIT) {
                                AppPreference.ImageDiskCacheSize.defaultValue * AppPreference.MEGA_BIT
                            } else {
                                it
                            }
                        },
                    okHttpClient = okHttpClient,
                    debugLogging = false,
                    enableCache = true,
                )
                LaunchedEffect(appPreferences.debugLogging) {
                    DebugLogTree.INSTANCE.enabled = appPreferences.debugLogging
                }

                var lastInteractionTime by remember { mutableLongStateOf(System.currentTimeMillis()) }
                val idleTimeout = 10 * 60 * 1000L // 10 minutes

                forceInteractionReset = {
                    lastInteractionTime = System.currentTimeMillis()
                }

                LaunchedEffect(Unit) {
                    while (true) {
                        delay(1000)
                        val currentTime = System.currentTimeMillis()
                        if (currentTime - lastInteractionTime >= idleTimeout &&
                            navigationManager.backStack.lastOrNull() !is Destination.AmbientScreensaver &&
                            navigationManager.backStack.lastOrNull() !is Destination.Playback) {
                            navigationManager.navigateTo(Destination.AmbientScreensaver)
                        }
                    }
                }

                CompositionLocalProvider(LocalImageUrlService provides imageUrlService) {
                    WholphinTheme(
                        true,
                        appThemeColors = appPreferences.interfacePreferences.appThemeColors,
                    ) {
                        Surface(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(MaterialTheme.colorScheme.background)
                                .onPreviewKeyEvent {
                                    lastInteractionTime = System.currentTimeMillis()
                                    if (dismissAmbientScreensaverIfVisible(consumeIfRecentlyDismissed = true)) {
                                        return@onPreviewKeyEvent true
                                    }
                                    false
                                },
                            shape = RectangleShape,
                        ) {
                            val backStack = setupNavigationManager.backStack
                            NavDisplay(
                                backStack = backStack,
                                onBack = { backStack.removeLastOrNull() },
                                entryDecorators =
                                    listOf(
                                        rememberSaveableStateHolderNavEntryDecorator(),
                                        rememberViewModelStoreNavEntryDecorator(),
                                    ),
                                entryProvider = { key ->
                                    key as SetupDestination
                                    Timber.d("BREADCRUMB: MAIN - NavDisplay provided key: $key")
                                    NavEntry(key) {
                                        when (key) {
                                            SetupDestination.Loading -> WholphinSplashScreen()
                                            SetupDestination.ServerList -> SwitchServerContent(Modifier.fillMaxSize())
                                            is SetupDestination.UserList -> SwitchUserContent(currentServer = key.server, Modifier.fillMaxSize())
                                            is SetupDestination.AppContent -> {
                                                val current = key.current
                                                ProvideLocalClock {
                                                    val appPreferences by userPreferencesDataStore.data.collectAsState(appPreferences)
                                                    val preferences = remember(appPreferences) { UserPreferences(appPreferences) }
                                                    var showContent by remember { mutableStateOf(true) }

                                                    LifecycleEventEffect(Lifecycle.Event.ON_STOP) {
                                                        if (!preferences.appPreferences.signInAutomatically) {
                                                            showContent = false
                                                        }
                                                    }

                                                    LifecycleEventEffect(Lifecycle.Event.ON_START) {
                                                        showContent = true
                                                    }

                                                    if (showContent) {
                                                        val requestedDestination = remember(intent) { intent?.let(::extractDestination) }
                                                        ApplicationContent(
                                                            user = current.user,
                                                            server = current.server,
                                                            startDestination = requestedDestination ?: Destination.Home(),
                                                            navigationManager = navigationManager,
                                                            preferences = preferences,
                                                            onInteraction = {
                                                                lastInteractionTime = System.currentTimeMillis()
                                                                dismissAmbientScreensaverIfVisible()
                                                            },
                                                            modifier = Modifier.fillMaxSize(),
                                                        )
                                                    } else {
                                                        Box(
                                                            modifier = Modifier.size(200.dp),
                                                            contentAlignment = Alignment.Center,
                                                        ) {
                                                            CircularProgressIndicator(
                                                                color = MaterialTheme.colorScheme.border,
                                                                modifier = Modifier.align(Alignment.Center),
                                                            )
                                                        }
                                                    }
                                                }
                                            }
                                        }
                                    }
                                },
                            )
                        }
                    }
                }
            }
        }
    }

    @Composable
    fun WholphinSplashScreen() {
        var startAnimation by remember { mutableStateOf(false) }
        val scale by animateFloatAsState(
            targetValue = if (startAnimation) 1.2f else 1f,
            animationSpec = tween(durationMillis = 1000),
            label = "logo_scale"
        )

        LaunchedEffect(Unit) {
            startAnimation = true
            delay(1500)
        }

        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = R.drawable.app_icon),
                contentDescription = "Logo",
                modifier = Modifier
                    .size(200.dp)
                    .scale(scale)
            )
        }
    }

    override fun onResume() {
        super.onResume()
        Timber.d("BREADCRUMB: MAIN - onResume")
        lifecycleScope.launchIO {
            appUpgradeHandler.run()
        }

        forceInteractionReset?.invoke()
        dismissAmbientScreensaverIfVisible()
    }

    private fun dismissAmbientScreensaverIfVisible(
        consumeIfRecentlyDismissed: Boolean = false,
    ): Boolean {
        if (!::navigationManager.isInitialized) return false

        val now = System.currentTimeMillis()
        val dismissedRecently = now - lastAmbientDismissAt <= AMBIENT_DISMISS_DEBOUNCE_MS
        val currentDestination = navigationManager.backStack.lastOrNull()

        if (currentDestination is Destination.AmbientScreensaver) {
            if (!dismissedRecently) {
                lastAmbientDismissAt = now
                navigationManager.goBack()
            }
            return true
        }

        return consumeIfRecentlyDismissed && dismissedRecently
    }

    override fun onRestart() {
        super.onRestart()
        Timber.d("BREADCRUMB: MAIN - onRestart")

        if (justCreated) {
            justCreated = false
            Timber.d("BREADCRUMB: MAIN - onRestart skipped (just created)")
            return
        }
        justCreated = false

        lifecycleScope.launch(Dispatchers.IO) {
            try {
                val prefs = userPreferencesDataStore.data.firstOrNull()
                Timber.d("BREADCRUMB: MAIN - onRestart: signInAutomatically=${prefs?.signInAutomatically}, serverId=${prefs?.currentServerId}, userId=${prefs?.currentUserId}")

                if (prefs?.signInAutomatically == false) {
                    val currentServer = viewModel.serverRepository.currentServer.value
                    if (currentServer != null) {
                        Timber.d("BREADCRUMB: MAIN - onRestart (Auto sign-in OFF), going to UserList for server ${currentServer.name}")
                        setupNavigationManager.backStack.clear()
                        setupNavigationManager.backStack.add(SetupDestination.UserList(currentServer))
                        return@launch
                    } else {
                        Timber.d("BREADCRUMB: MAIN - onRestart (Auto sign-in OFF), no current server, running appStart")
                        viewModel.appStart()
                        return@launch
                    }
                }

                val serverId = prefs?.currentServerId?.toUUIDOrNull()
                val userId = prefs?.currentUserId?.toUUIDOrNull()
                Timber.d("BREADCRUMB: MAIN - onRestart: parsed serverId=$serverId, userId=$userId")

                if (serverId != null && userId != null) {
                    val currentSession = viewModel.serverRepository.current.value
                    if (currentSession != null &&
                        currentSession.server.id == serverId &&
                        currentSession.user.id == userId) {
                        Timber.d("BREADCRUMB: MAIN - onRestart: session already active for ${currentSession.user.name}, skipping restore")
                        return@launch
                    }

                    Timber.d("BREADCRUMB: MAIN - onRestart: attempting restoreSession")
                    try {
                        val result = withTimeoutOrNull(8000) {
                            viewModel.serverRepository.restoreSession(serverId, userId)
                        }
                        if (result != null) {
                            Timber.d("BREADCRUMB: MAIN - onRestart session restored for ${result.user.name}")
                            val currentDest = setupNavigationManager.backStack.lastOrNull()
                            if (currentDest !is SetupDestination.AppContent) {
                                Timber.d("BREADCRUMB: MAIN - onRestart: navigating to AppContent after restore")
                                setupNavigationManager.navigateTo(SetupDestination.AppContent(result))
                            }
                        } else {
                            Timber.w("BREADCRUMB: MAIN - onRestart: restoreSession timed out or returned null")
                            val knownServer = viewModel.serverRepository.currentServer.value
                            if (knownServer != null) {
                                Timber.d("BREADCRUMB: MAIN - onRestart: going to UserList for known server ${knownServer.name}")
                                setupNavigationManager.navigateTo(SetupDestination.UserList(knownServer))
                            } else {
                                Timber.d("BREADCRUMB: MAIN - onRestart: no known server, running appStart")
                                viewModel.appStart()
                            }
                        }
                    } catch (ex: Exception) {
                        Timber.w(ex, "BREADCRUMB: MAIN - onRestart restore failed")
                        val knownServer = viewModel.serverRepository.currentServer.value
                        if (knownServer != null) {
                            Timber.d("BREADCRUMB: MAIN - onRestart: exception during restore, going to UserList for ${knownServer.name}")
                            setupNavigationManager.navigateTo(SetupDestination.UserList(knownServer))
                        } else {
                            viewModel.appStart()
                        }
                    }
                } else {
                    Timber.d("BREADCRUMB: MAIN - onRestart: no saved IDs in DataStore")
                    val knownServer = viewModel.serverRepository.currentServer.value
                    if (knownServer != null) {
                        Timber.d("BREADCRUMB: MAIN - onRestart: no IDs but have server, going to UserList for ${knownServer.name}")
                        setupNavigationManager.navigateTo(SetupDestination.UserList(knownServer))
                    } else {
                        Timber.d("BREADCRUMB: MAIN - onRestart: no IDs, no server, running appStart")
                        viewModel.appStart()
                    }
                }
            } catch (ex: InvalidStatusException) {
                Timber.w("BREADCRUMB: MAIN - onRestart HTTP ${ex.status}")
                if (ex.status == 401) {
                    Timber.w("BREADCRUMB: MAIN - onRestart 401 Unauthorized, running appStart")
                    viewModel.appStart()
                } else {
                    viewModel.appStart()
                }
            } catch (ex: Exception) {
                Timber.e(ex, "BREADCRUMB: MAIN - onRestart unexpected error, running appStart")
                viewModel.appStart()
            }
        }
    }

    override fun onStop() {
        super.onStop()
        Timber.d("BREADCRUMB: MAIN - onStop")
        tvProviderSchedulerService.launchOneTimeRefresh()
    }

    override fun onPause() {
        super.onPause()
        Timber.d("BREADCRUMB: MAIN - onPause")
    }

    override fun onStart() {
        super.onStart()
        Timber.d("BREADCRUMB: MAIN - onStart")
    }

    override fun onDestroy() {
        super.onDestroy()
        Timber.d("BREADCRUMB: MAIN - onDestroy")
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        extractDestination(intent)?.let {
            navigationManager.replace(it)
        }
    }

    private fun extractDestination(intent: Intent): Destination? =
        intent.let {
            val itemId = it.getStringExtra(INTENT_ITEM_ID)?.toUUIDOrNull()
            val type = it.getStringExtra(INTENT_ITEM_TYPE)?.let(BaseItemKind::fromNameOrNull)
            if (itemId != null && type != null) {
                val seriesId = it.getStringExtra(INTENT_SERIES_ID)?.toUUIDOrNull()
                val seasonId = it.getStringExtra(INTENT_SEASON_ID)?.toUUIDOrNull()
                val episodeNumber = it.getIntExtra(INTENT_EPISODE_NUMBER, -1)
                val seasonNumber = it.getIntExtra(INTENT_SEASON_NUMBER, -1)
                if (seriesId != null && seasonId != null && episodeNumber >= 0 && seasonNumber >= 0) {
                    Destination.SeriesOverview(
                        itemId = seriesId,
                        type = BaseItemKind.SERIES,
                        seasonEpisode = SeasonEpisodeIds(
                            seasonId = seasonId,
                            seasonNumber = seasonNumber,
                            episodeId = itemId,
                            episodeNumber = episodeNumber,
                        ),
                    )
                } else {
                    Destination.MediaItem(itemId, type)
                }
            } else {
                null
            }
        }

    fun changeDisplayMode(modeId: Int) {
        lifecycleScope.launch(Dispatchers.Main + ExceptionHandler(autoToast = true)) {
            val attrs = window.attributes
            if (attrs.preferredDisplayModeId != modeId) {
                Timber.d("Switch preferredDisplayModeId to %s", modeId)
                window.attributes = attrs.apply { preferredDisplayModeId = modeId }
            }
        }
    }

    companion object {
        private const val AMBIENT_DISMISS_DEBOUNCE_MS = 750L

        const val INTENT_ITEM_ID = "itemId"
        const val INTENT_ITEM_TYPE = "itemType"
        const val INTENT_SERIES_ID = "seriesId"
        const val INTENT_EPISODE_NUMBER = "epNum"
        const val INTENT_SEASON_NUMBER = "seaNum"
        const val INTENT_SEASON_ID = "seaId"

        lateinit var instance: MainActivity
            private set
    }
}

@HiltViewModel
class MainActivityViewModel
@Inject
constructor(
    private val preferences: DataStore<AppPreferences>,
    val serverRepository: ServerRepository,
    private val navigationManager: SetupNavigationManager,
    private val deviceProfileService: DeviceProfileService,
    private val backdropService: BackdropService,
) : ViewModel() {
    fun appStart() {
        viewModelScope.launchIO {
            Timber.d("BREADCRUMB: VIEWMODEL - appStart sequence initiated")
            try {
                delay(1500)
                val prefs = preferences.data.firstOrNull() ?: AppPreferences.getDefaultInstance()
                val userHasPin = serverRepository.currentUser.value?.hasPin == true
                val hardcodedUrl = "http://10.0.0.105:8096"
                val hardcodedServerId = UUID.nameUUIDFromBytes(hardcodedUrl.toByteArray())

                val api = serverRepository.jellyfin.createApi(hardcodedUrl)
                val systemInfo = try {
                    api.systemApi.getPublicSystemInfo().content
                } catch (ex: Exception) {
                    Timber.w(ex, "BREADCRUMB: VIEWMODEL - appStart failed to fetch system info")
                    null
                }

                val server = JellyfinServer(
                    id = systemInfo?.id?.toUUIDOrNull() ?: hardcodedServerId,
                    name = systemInfo?.serverName ?: "Jellyfin",
                    url = hardcodedUrl,
                    version = systemInfo?.version
                )

                serverRepository.addAndChangeServer(server)
                Timber.d("BREADCRUMB: VIEWMODEL - appStart: server registered, signInAuto=${prefs.signInAutomatically}, userHasPin=$userHasPin")

                if (prefs.signInAutomatically && !userHasPin) {
                    val savedUserId = prefs.currentUserId?.toUUIDOrNull()
                    Timber.d("BREADCRUMB: VIEWMODEL - appStart: attempting restoreSession for userId=$savedUserId")
                    val current = serverRepository.restoreSession(server.id, savedUserId)
                    if (current != null) {
                        if (current.user.hasPin) {
                            Timber.d("BREADCRUMB: VIEWMODEL - restoreSession SUCCESS, user has pin -> UserList")
                            navigationManager.navigateTo(SetupDestination.UserList(current.server))
                        } else {
                            Timber.d("BREADCRUMB: VIEWMODEL - restoreSession SUCCESS -> AppContent")
                            navigationManager.navigateTo(SetupDestination.AppContent(current))
                        }
                    } else {
                        Timber.d("BREADCRUMB: VIEWMODEL - restoreSession FAILED -> UserList")
                        navigationManager.navigateTo(SetupDestination.UserList(server))
                    }
                } else {
                    Timber.d("BREADCRUMB: VIEWMODEL - auto sign-in disabled or user has pin -> UserList")
                    navigationManager.navigateTo(SetupDestination.UserList(server))
                }
            } catch (ex: Exception) {
                Timber.e(ex, "BREADCRUMB: VIEWMODEL - Error during appStart")
                val hardcodedUrl = "http://10.0.0.105:8096"
                val hardcodedServerId = UUID.nameUUIDFromBytes(hardcodedUrl.toByteArray())
                navigationManager.navigateTo(SetupDestination.UserList(JellyfinServer(hardcodedServerId, "Jellyfin", hardcodedUrl, null)))
            }
        }
    }
}
