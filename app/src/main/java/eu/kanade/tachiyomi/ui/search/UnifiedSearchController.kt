package eu.kanade.tachiyomi.ui.search

import android.os.Bundle
import androidx.compose.runtime.Composable
import eu.kanade.tachiyomi.ui.base.controller.BaseCoroutineComposeController
import eu.kanade.tachiyomi.ui.main.SearchControllerInterface
import yokai.presentation.search.UnifiedSearchScreen

class UnifiedSearchController(
    val query: String? = null,
    bundle: Bundle? = null
) : BaseCoroutineComposeController<UnifiedSearchPresenter>(bundle), SearchControllerInterface {

    override val presenter = UnifiedSearchPresenter(query)

    @Composable
    override fun ScreenContent() {
        UnifiedSearchScreen(presenter = presenter)
    }

    override fun searchTitle(title: String?): String? {
        return "Search"
    }
}
