package eu.kanade.tachiyomi.ui.suggestions

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
import androidx.core.view.WindowInsetsCompat.Type.systemBars
import com.bluelinelabs.conductor.ControllerChangeHandler
import com.bluelinelabs.conductor.ControllerChangeType
import eu.kanade.tachiyomi.App
import eu.kanade.tachiyomi.R
import eu.kanade.tachiyomi.databinding.SuggestionsControllerBinding
import eu.kanade.tachiyomi.domain.manga.models.Manga
import eu.kanade.tachiyomi.ui.base.MaterialMenuSheet
import eu.kanade.tachiyomi.ui.base.controller.BaseCoroutineController
import eu.kanade.tachiyomi.ui.main.FloatingSearchInterface
import eu.kanade.tachiyomi.ui.main.MainActivity
import eu.kanade.tachiyomi.ui.main.RootSearchInterface
import eu.kanade.tachiyomi.ui.manga.MangaDetailsController
import eu.kanade.tachiyomi.util.system.dpToPx
import eu.kanade.tachiyomi.util.system.launchIO
import eu.kanade.tachiyomi.util.system.toast
import eu.kanade.tachiyomi.util.system.withUIContext
import eu.kanade.tachiyomi.util.view.activityBinding
import eu.kanade.tachiyomi.util.view.doOnApplyWindowInsetsCompat
import eu.kanade.tachiyomi.util.view.setStyle
import eu.kanade.tachiyomi.util.view.withFadeTransaction
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import uy.kohesive.injekt.Injekt
import uy.kohesive.injekt.api.get
import yokai.presentation.suggestions.SuggestionsScreen
import yokai.presentation.theme.YokaiTheme

class SuggestionsController(
    bundle: Bundle? = null,
) : BaseCoroutineController<SuggestionsControllerBinding, SuggestionsPresenter>(bundle),
    RootSearchInterface,
    FloatingSearchInterface {

    override val presenter: SuggestionsPresenter by lazy {
        SuggestionsPresenter(context = applicationContext ?: Injekt.get<App>())
    }

    private var suggestionsCanScrollUp = false
    private var statusBarHeight = 0

    init {
        setHasOptionsMenu(true)
    }

    override fun createBinding(inflater: LayoutInflater) = SuggestionsControllerBinding.inflate(inflater)

    override fun getTitle(): String? {
        return "Suggestions"
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
        binding.swipeRefresh.setOnChildScrollUpCallback { _, _ -> suggestionsCanScrollUp }
        binding.root.doOnApplyWindowInsetsCompat { _, insets, _ ->
            statusBarHeight = insets.getInsets(systemBars()).top
            updateSwipeRefreshOffset()
        }
        updateSwipeRefreshOffset()

        presenter.state
            .onEach { state -> binding.swipeRefresh.isRefreshing = state.isLoading }
            .launchIn(viewScope)

        binding.composeView.setViewCompositionStrategy(ViewCompositionStrategy.DisposeOnViewTreeLifecycleDestroyed)
        binding.composeView.setContent {
            YokaiTheme {
                SuggestionsScreen(
                    presenter = presenter,
                    contentPadding = suggestionsContentPadding(),
                    onMangaClick = ::openManga,
                    onCanScrollUpChanged = { suggestionsCanScrollUp = it },
                )
            }
        }
    }

    override fun onChangeStarted(handler: ControllerChangeHandler, type: ControllerChangeType) {
        super.onChangeStarted(handler, type)
        if (type.isEnter) {
            syncAppBarMode()
            updateSwipeRefreshOffset()
        }
    }

    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.suggestions, menu)
    }

    override fun onOptionsItemSelected(item: MenuItem): Boolean {
        return when (item.itemId) {
            R.id.action_filter -> {
                showFilterSheet()
                true
            }
            else -> super.onOptionsItemSelected(item)
        }
    }

    private fun showFilterSheet() {
        val activity = activity ?: return
        val state = presenter.state.value
        val reasons = state.suggestions.keys.toList()
        val items = buildList {
            add(
                MaterialMenuSheet.MenuSheetItem(
                    id = FILTER_ALL,
                    drawable = R.drawable.ic_filter_list_24dp,
                    text = "All reasons",
                    endDrawableRes = R.drawable.ic_check_24dp,
                ),
            )
            reasons.forEachIndexed { index, reason ->
                add(
                    MaterialMenuSheet.MenuSheetItem(
                        id = index + 1,
                        drawable = R.drawable.ic_label_outline_24dp,
                        text = reason,
                        endDrawableRes = R.drawable.ic_check_24dp,
                    ),
                )
            }
        }
        val selectedId = state.selectedReason
            ?.let { selected -> reasons.indexOf(selected).takeIf { it >= 0 }?.plus(1) }
            ?: FILTER_ALL

        MaterialMenuSheet(
            activity = activity,
            items = items,
            title = "Filter suggestions",
            selectedId = selectedId,
        ) { _, itemId ->
            presenter.setSelectedReason(
                if (itemId == FILTER_ALL) {
                    null
                } else {
                    reasons.getOrNull(itemId - 1)
                },
            )
            true
        }.show()
    }

    private fun updateSwipeRefreshOffset() {
        if (!isBindingInitialized) return
        val headerHeight = statusBarHeight + currentAppBarHeight()
        binding.swipeRefresh.setProgressViewOffset(
            true,
            headerHeight + (-60).dpToPx,
            headerHeight + 10.dpToPx,
        )
    }

    private fun syncAppBarMode() {
        activityBinding?.appBar?.useTabsInPreLayout = false
        activityBinding?.appBar?.setToolbarModeBy(this)
        activityBinding?.appBar?.hideBigView(false)
    }

    @Composable
    private fun suggestionsContentPadding(): PaddingValues {
        val density = LocalDensity.current
        val top = WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
        val bottom = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val appBarHeight = with(density) { currentAppBarHeight().toDp() }
        return PaddingValues(
            start = 16.dp,
            top = top + appBarHeight + 16.dp,
            end = 16.dp,
            bottom = bottom + 96.dp,
        )
    }

    private fun currentAppBarHeight(): Int {
        return (activity as? MainActivity)?.bigToolbarHeight(
            includeSearchToolbar = true,
            includeTabs = false,
            includeLargeToolbar = true,
        ) ?: 0
    }

    private fun openManga(manga: Manga) {
        viewScope.launchIO {
            val localManga = presenter.getOrCreateLocalManga(manga)
            withUIContext {
                if (localManga?.id == null) {
                    activity?.toast("Unable to open suggestion")
                    return@withUIContext
                }
                router.pushController(MangaDetailsController(localManga, true).withFadeTransaction())
            }
        }
    }

    private companion object {
        const val FILTER_ALL = 0
    }
}
