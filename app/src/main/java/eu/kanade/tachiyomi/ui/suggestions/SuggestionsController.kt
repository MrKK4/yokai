package eu.kanade.tachiyomi.ui.suggestions

import android.app.Application
import android.content.res.ColorStateList
import android.net.ConnectivityManager
import android.net.Network
import android.os.Bundle
import android.view.LayoutInflater
import android.view.Menu
import android.view.MenuInflater
import android.view.MenuItem
import android.view.View
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.statusBars
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.compose.ui.unit.dp
import androidx.core.graphics.ColorUtils
import androidx.core.view.ScrollingView
import androidx.core.view.WindowInsetsCompat.Type.systemBars
import com.bluelinelabs.conductor.ControllerChangeHandler
import com.bluelinelabs.conductor.ControllerChangeType
import com.google.android.material.snackbar.Snackbar
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.databinding.SuggestionsControllerBinding
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.ui.base.MaterialMenuSheet
import eu.kanade.tachiyomi.ui.base.controller.BaseCoroutineController
import eu.kanade.tachiyomi.ui.main.BottomSheetController
import eu.kanade.tachiyomi.ui.main.FloatingSearchInterface
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.main.RootSearchInterface
import eu.kanade.tachiyomi.ui.manga.MangaDetailsController
import eu.kanade.tachiyomi.ui.source.globalsearch.GlobalSearchController
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.getResourceColor
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.system.withUIContext
import eu.kanade.tachiyomi.util.view.activityBinding
import eu.kanade.tachiyomi.util.view.doOnApplyWindowInsetsCompat
import eu.kanade.tachiyomi.util.view.setOnQueryTextChangeListener
import eu.kanade.tachiyomi.util.view.setStyle
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.domain.suggestions.SuggestionSortOrder
import yokai.i18n.MR
import yokai.presentation.suggestions.SuggestionsScreen
import yokai.presentation.theme.YokaiTheme
import yokai.util.lang.getString

class SuggestionsController(
    bundle: Bundle? = null,
) : BaseCoroutineController<SuggestionsControllerBinding, SuggestionsPresenter>(bundle),
    RootSearchInterface,
    FloatingSearchInterface,
    BottomSheetController {

    override val presenter: SuggestionsPresenter = sharedPresenter

    private var suggestionsCanScrollUp = false
    private var statusBarHeight = 0
    private val appBarScrollProxy = ComposeAppBarScrollProxy()
    private var isNavigatingToManga = false

    private val connectivityCallback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            if (presenter.hasNetworkError()) {
                presenter.refresh()
            }
        }
    }

    init {
        setHasOptionsMenu(true)
    }

    override fun createBinding(inflater: LayoutInflater) = SuggestionsControllerBinding.inflate(inflater)

    override fun getTitle(): String? {
        return view?.context?.getString(MR.strings.suggestions) ?: "Suggestions"
    }

    override fun getSearchTitle(): String? {
        return searchTitle("suggestions")
    }

    override fun onViewCreated(view: View) {
        super.onViewCreated(view)
        setTitle()
        syncAppBarMode()

        binding.swipeRefresh.setStyle()
        binding.swipeRefresh.setOnRefreshListener { presenter.refresh() }
        binding.swipeRefresh.setOnChildScrollUpCallback { _, _ ->
            // When already refreshing, tell SwipeRefreshLayout the child can scroll up so it
            // doesn't re-engage the pull gesture and visually "stop" the in-progress refresh.
            suggestionsCanScrollUp || binding.swipeRefresh.isRefreshing
        }
        binding.root.doOnApplyWindowInsetsCompat { _, insets, _ ->
            statusBarHeight = insets.getInsets(systemBars()).top
            updateSwipeRefreshOffset()
        }
        updateSwipeRefreshOffset()

        presenter.state
            .onEach { state -> binding.swipeRefresh.isRefreshing = state.isLoading }
            .launchIn(viewScope)

        // Bug 8b: if the background worker has been failing for >24h, show a single
        // non-intrusive toast so the user knows suggestions may be stale.
        val preferences: eu.kanade.tachiyomi.data.preference.PreferencesHelper = Injekt.get()
        val lastFailed = preferences.suggestionsWorkerLastFailedAt().get()
        val dayMillis = 24L * 60 * 60 * 1_000
        if (lastFailed > 0L && System.currentTimeMillis() - lastFailed > dayMillis) {
            activity?.toast(MR.strings.suggestions_background_refresh_failed)
            // Clear so the banner doesn't show on every tab visit.
            preferences.suggestionsWorkerLastFailedAt().set(0L)
        }

        val connectivityManager = view.context.getSystemService(Application.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager.registerDefaultNetworkCallback(connectivityCallback)

        binding.composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        binding.composeView.setContent {
            YokaiTheme {
                SuggestionsScreen(
                    presenter = presenter,
                    contentPadding = suggestionsContentPadding(),
                    onMangaClick = ::openManga,
                    onCanScrollUpChanged = ::onSuggestionsCanScrollUpChanged,
                    onExpandSection = presenter::expandSection,
                )
            }
        }
    }

    override fun onDestroyView(view: View) {
        super.onDestroyView(view)
        val connectivityManager = view.context.getSystemService(Application.CONNECTIVITY_SERVICE) as ConnectivityManager
        connectivityManager.unregisterNetworkCallback(connectivityCallback)
    }

    override fun onChangeStarted(handler: ControllerChangeHandler, type: ControllerChangeType) {
        super.onChangeStarted(handler, type)
        if (type.isEnter) {
            isNavigatingToManga = false
            syncAppBarMode()
            updateSwipeRefreshOffset()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.suggestions, menu)

        val v2Item = menu.findItem(R.id.action_toggle_v2)
        setupVersionAction(v2Item)

        activityBinding?.searchToolbar?.searchQueryHint = view?.context?.getString(MR.strings.global_search)
        setOnQueryTextChangeListener(activityBinding?.searchToolbar?.searchView, true) {
            if (!it.isNullOrBlank()) performGlobalSearch(it)
            true
        }
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_sort -> {
                showSortSheet()
                true
            }
            R.id.action_toggle_v2 -> {
                toggleSuggestionsVersion()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun setupVersionAction(item: MenuItem?) {
        val actionView = item?.actionView as? SuggestionsVersionActionView ?: return
        val isV2Enabled = presenter.isSuggestionsV2Enabled()
        item.title = suggestionsModeTitle(isV2Enabled)
        actionView.setVersion(isV2Enabled)
        actionView.setOnClickListener { toggleSuggestionsVersion() }
    }

    private fun toggleSuggestionsVersion() {
        val nextIsV2Enabled = !presenter.isSuggestionsV2Enabled()
        if (!presenter.setSuggestionsV2Enabled(nextIsV2Enabled)) return
        updateVersionAction(nextIsV2Enabled)
        showSuggestionsVersionPopup(nextIsV2Enabled)
    }

    private fun updateVersionAction(isV2Enabled: Boolean) {
        val title = suggestionsModeTitle(isV2Enabled)
        listOfNotNull(
            activityBinding?.toolbar?.menu?.findItem(R.id.action_toggle_v2),
            activityBinding?.searchToolbar?.menu?.findItem(R.id.action_toggle_v2),
        ).forEach { item ->
            item.title = title
            (item.actionView as? SuggestionsVersionActionView)?.setVersion(isV2Enabled)
        }
    }

    private fun showSuggestionsVersionPopup(isV2Enabled: Boolean) {
        val root = view ?: return
        Snackbar.make(
            root,
            root.context.getString(
                if (isV2Enabled) {
                    MR.strings.suggestions_for_you_activated
                } else {
                    MR.strings.suggestions_surprise_me_activated
                },
            ),
            Snackbar.LENGTH_SHORT,
        ).apply {
            view.backgroundTintList = ColorStateList.valueOf(
                ColorUtils.setAlphaComponent(
                    root.context.getResourceColor(R.attr.colorSurface),
                    VERSION_POPUP_ALPHA,
                ),
            )
            setTextColor(root.context.getResourceColor(R.attr.colorOnSurface))
            show()
        }
    }

    private fun showFilterSheet() {
        val activity = activity ?: return
        val state = presenter.state.value
        val sectionKeys = state.suggestions.keys.toList()
        val items = buildList {
            add(
                MaterialMenuSheet.MenuSheetItem(
                    id = FILTER_ALL,
                    drawable = R.drawable.ic_filter_list_24dp,
                    text = activity.getString(MR.strings.suggestions_filter_all),
                    endDrawableRes = R.drawable.ic_check_24dp,
                ),
            )
            sectionKeys.forEachIndexed { index, sectionKey ->
                add(
                    MaterialMenuSheet.MenuSheetItem(
                        id = index + 1,
                        drawable = R.drawable.ic_label_outline_24dp,
                        text = state.sectionDisplayNames[sectionKey] ?: sectionKey,
                        endDrawableRes = R.drawable.ic_check_24dp,
                    ),
                )
            }
        }
        val selectedId = state.selectedSectionKey
            ?.let { selected -> sectionKeys.indexOf(selected).takeIf { it >= 0 }?.plus(1) }
            ?: FILTER_ALL

        MaterialMenuSheet(
            activity = activity,
            items = items,
            title = activity.getString(MR.strings.suggestions_filter_title),
            selectedId = selectedId,
        ) { _, itemId ->
            presenter.setSelectedSectionKey(
                if (itemId == FILTER_ALL) {
                    null
                } else {
                    sectionKeys.getOrNull(itemId - 1)
                },
            )
            true
        }.show()
    }

    private fun showSortSheet() {
        val activity = activity ?: return
        val selectedId = when (presenter.state.value.sortOrder) {
            SuggestionSortOrder.Popular -> SORT_POPULAR
            SuggestionSortOrder.Latest -> SORT_LATEST
        }
        val items = listOf(
            MaterialMenuSheet.MenuSheetItem(
                id = SORT_POPULAR,
                drawable = R.drawable.ic_sort_24dp,
                text = activity.getString(MR.strings.popular),
                endDrawableRes = R.drawable.ic_check_24dp,
            ),
            MaterialMenuSheet.MenuSheetItem(
                id = SORT_LATEST,
                drawable = R.drawable.ic_new_releases_outline_24dp,
                text = activity.getString(MR.strings.latest),
                endDrawableRes = R.drawable.ic_check_24dp,
            ),
            MaterialMenuSheet.MenuSheetItem(
                id = SORT_FILTER,
                drawable = R.drawable.ic_filter_list_24dp,
                text = activity.getString(MR.strings.suggestions_filter_by_section),
            ),
            MaterialMenuSheet.MenuSheetItem(
                id = SORT_TAG_FILTER,
                drawable = R.drawable.ic_label_outline_24dp,
                text = activity.getString(MR.strings.suggestions_exclude_tags),
            ),
        )

        MaterialMenuSheet(
            activity = activity,
            items = items,
            title = activity.getString(MR.strings.suggestions_sort_title),
            selectedId = selectedId,
        ) { _, itemId ->
            when (itemId) {
                SORT_FILTER -> binding.root.post { showFilterSheet() }
                SORT_TAG_FILTER -> binding.root.post { presenter.showTagFilterSheet() }
                else -> presenter.setSortOrder(
                    when (itemId) {
                        SORT_LATEST -> SuggestionSortOrder.Latest
                        else -> SuggestionSortOrder.Popular
                    },
                )
            }
            true
        }.show()
    }

    private fun updateSwipeRefreshOffset() {
        if (!isBindingInitialized) return
        if (binding.swipeRefresh.isRefreshing) return   // don't interrupt the active refresh animation
        val headerHeight = statusBarHeight + currentAppBarHeight()
        binding.swipeRefresh.setProgressViewOffset(
            true,
            headerHeight + (-60).dpToPx,
            headerHeight + 10.dpToPx,
        )
    }

    private fun syncAppBarMode() {
        val appBar = activityBinding?.appBar ?: return
        appBar.lockYPos = false
        appBar.useTabsInPreLayout = false
        appBar.setToolbarModeBy(this)
        appBar.hideBigView(false)
        appBarScrollProxy.verticalOffset = if (suggestionsCanScrollUp) {
            Int.MAX_VALUE / 4
        } else {
            0
        }
        appBar.updateAppBarAfterY(appBarScrollProxy)
    }

    private fun onSuggestionsCanScrollUpChanged(canScrollUp: Boolean) {
        if (suggestionsCanScrollUp == canScrollUp) return
        suggestionsCanScrollUp = canScrollUp
        syncAppBarMode()
        updateSwipeRefreshOffset()
    }

    @Composable
    private fun suggestionsContentPadding(): PaddingValues {
        val density = LocalDensity.current
        val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val appBarHeight = with(density) {
            currentAppBarHeight().toDp()
                .coerceAtLeast(minimumExpandedAppBarHeight())
        }
        return PaddingValues(
            start = 16.dp,
            top = top + appBarHeight + 24.dp,
            end = 16.dp,
            bottom = bottom + 96.dp,
        )
    }

    private fun minimumExpandedAppBarHeight() =
        if (activityBinding?.appBar?.useLargeToolbar == true) {
            228.dp
        } else {
            0.dp
        }

    private fun currentAppBarHeight(): Int {
        return (activity as? MainActivity)?.bigToolbarHeight(
            includeSearchToolbar = true,
            includeTabs = false,
            includeLargeToolbar = true,
        ) ?: 0
    }

    private fun openManga(manga: Manga) {
        if (isNavigatingToManga) return
        isNavigatingToManga = true
        viewScope.launchIO {
            val localManga = presenter.getOrCreateLocalManga(manga)
            withUIContext {
                if (localManga?.id == null) {
                    activity?.toast(MR.strings.suggestions_open_error)
                    isNavigatingToManga = false
                    return@withUIContext
                }
                // Suppress the expanded sheet only after the DB lookup succeeds,
                // so the user doesn't see the sheet vanish before navigation starts.
                presenter.suppressExpandSheet()
                presenter.dismissExpandSheet()
                router.pushController(MangaDetailsController(localManga, true).withFadeTransaction())
            }
        }
    }

    private fun suggestionsModeTitle(isV2Enabled: Boolean): String {
        val context = view?.context ?: activity
        return context?.getString(
            if (isV2Enabled) {
                MR.strings.suggestions_for_you
            } else {
                MR.strings.suggestions_surprise_me
            },
        ) ?: if (isV2Enabled) {
            "For you"
        } else {
            "Surprise me"
        }
    }

    // BottomSheetController — re-tapping the suggestions tab scrolls to top and refreshes.
    override fun showSheet() = toggleSheet()
    override fun hideSheet() = toggleSheet()
    override fun toggleSheet() {
        presenter.refresh()
    }

    private fun performGlobalSearch(query: String) {
        router.pushController(GlobalSearchController(query).withFadeTransaction())
    }

    private class ComposeAppBarScrollProxy : ScrollingView {
        var verticalOffset: Int = 0

        override fun computeHorizontalScrollExtent(): Int = 0

        override fun computeHorizontalScrollOffset(): Int = 0

        override fun computeHorizontalScrollRange(): Int = 0

        override fun computeVerticalScrollExtent(): Int = 0

        override fun computeVerticalScrollOffset(): Int = verticalOffset

        override fun computeVerticalScrollRange(): Int = verticalOffset + 1
    }

    private companion object {
        val sharedPresenter by lazy {
            SuggestionsPresenter(context = Injekt.get<Application>())
        }

        const val FILTER_ALL = 0
        const val SORT_POPULAR = 1
        const val SORT_LATEST = 2
        const val SORT_FILTER = 3
        const val SORT_TAG_FILTER = 4
        const val VERSION_POPUP_ALPHA = 220
    }
}
