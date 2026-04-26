package eu.kanade.tachiyomi.ui.suggestions

import android.os.Bundle
import androidx.compose.runtime.Composable
import eu.kanade.tachiyomi.ui.base.controller.BaseCoroutineComposeController
import eu.kanade.tachiyomi.ui.main.SearchControllerInterface
import yokai.presentation.suggestions.SuggestionsScreen

class SuggestionsController(
    bundle: Bundle? = null
) : BaseCoroutineComposeController<SuggestionsPresenter>(bundle), SearchControllerInterface {

    override val presenter = SuggestionsPresenter()

    @Composable
    override fun ScreenContent() {
        SuggestionsScreen(presenter = presenter)
    }

    override fun searchTitle(title: String?): String? {
        return "Search suggestions"
    }
}
