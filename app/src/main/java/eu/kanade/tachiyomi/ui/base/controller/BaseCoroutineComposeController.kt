package eu.kanade.tachiyomi.ui.base.controller

import android.os.Bundle
import android.view.View
import eu.kanade.tachiyomi.ui.base.presenter.BaseCoroutinePresenter

abstract class BaseCoroutineComposeController<PS : BaseCoroutinePresenter<*>>(bundle: Bundle? = null) :
    BaseComposeController(bundle) {

    abstract val presenter: PS

    override fun onViewCreated(view: View) {
        super.onViewCreated(view)
        presenter.takeView(this)
        presenter.onCreate()
    }

    @Suppress("UNCHECKED_CAST")
    private fun <View> BaseCoroutinePresenter<View>.takeView(view: Any) = attachView(view as? View)

    override fun onDestroy() {
        super.onDestroy()
        presenter.onDestroy()
    }
}
