package eu.kanade.tachiyomi.ui.main

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.FrameLayout
import androidx.annotation.IdRes
import com.bluelinelabs.conductor.Controller
import com.bluelinelabs.conductor.Router
import com.bluelinelabs.conductor.RouterTransaction
import eu.kanade.tachiyomi.ui.base.controller.BaseController

/**
 * Hosts the bottom-nav tab controllers, each in its own child [Router] inside its own container.
 *
 * Switching tabs toggles container visibility (INVISIBLE/VISIBLE) instead of detaching/attaching
 * or recreating controllers. An INVISIBLE view stays attached AND laid out, so a switch is a
 * draw-only operation:
 *  - the tab's view (and its decoded cover bitmaps) survive untouched -> no re-inflation freeze,
 *  - Coil does not restart the cover requests on the way back -> no cover flicker,
 *  - no re-measure/layout -> the switch is effectively instant.
 *
 * Detail screens push onto the active tab's child router (a tab controller's [Controller.router]
 * resolves to its child router), so back navigation within a tab works and each tab keeps its own
 * stack; [MainActivity] delegates "visible controller" / "can go back" to [activeChildRouter].
 *
 * Because every tab controller is attached at once, only the active tab's root may own the shared
 * options menu — [MainActivity] toggles that on each switch via [onActiveTabChanged].
 */
class TabHostController(
    bundle: Bundle? = null,
) : BaseController(bundle) {

    override val shouldHideLegacyAppBar = false

    /** Set by [MainActivity] so the host knows how to build each tab's root controller. */
    var controllerFactory: ((Int) -> Controller)? = null

    /** Called after a tab's child router gets its root, so the activity can observe its changes. */
    var onChildRouterCreated: ((Router) -> Unit)? = null

    /** Called after the active tab changes so the activity can refresh the shared toolbar/menu. */
    var onActiveTabChanged: (() -> Unit)? = null

    private val containers = mutableMapOf<Int, FrameLayout>()
    private val childRouters = mutableMapOf<Int, Router>()
    private var pendingTabId: Int? = null

    @IdRes
    var activeTabId: Int = -1
        private set

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup, savedViewState: Bundle?): View {
        return FrameLayout(container.context).apply {
            layoutParams = ViewGroup.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            )
        }
    }

    override fun onViewCreated(view: View) {
        super.onViewCreated(view)
        // Re-bind tabs already created (e.g. after a config change) so Conductor restores their
        // child routers, then re-show whichever tab was active.
        childRouters.keys.toList().forEach { ensureTab(it) }
        val restoreId = pendingTabId ?: activeTabId.takeIf { it != -1 }
        pendingTabId = null
        restoreId?.let { switchTo(it) }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        super.onSaveInstanceState(outState)
        outState.putInt(STATE_ACTIVE_TAB, activeTabId)
    }

    override fun onRestoreInstanceState(savedInstanceState: Bundle) {
        super.onRestoreInstanceState(savedInstanceState)
        activeTabId = savedInstanceState.getInt(STATE_ACTIVE_TAB, -1)
    }

    val activeChildRouter: Router?
        get() = childRouters[activeTabId]

    fun childRouterFor(@IdRes id: Int): Router? = childRouters[id]

    /**
     * Every tab controller is attached at once, so without this they would all contribute items to
     * the shared toolbar menu (e.g. Recents' filter icon bleeding into Browse). Hide the options
     * menu for every controller except the visible (top) one of the active tab.
     */
    fun updateMenuVisibility() {
        childRouters.forEach { (id, childRouter) ->
            val top = childRouter.backstack.lastOrNull()?.controller
            childRouter.backstack.forEach { txn ->
                txn.controller.setOptionsMenuHidden(!(id == activeTabId && txn.controller === top))
            }
        }
    }

    private fun ensureTab(@IdRes id: Int): Router? {
        val root = view as? FrameLayout ?: return null
        val factory = controllerFactory ?: return null
        val childContainer = containers.getOrPut(id) {
            FrameLayout(root.context).apply {
                // Stable id (the nav menu id) so Conductor restores this child router after rotation.
                this.id = id
                layoutParams = FrameLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.MATCH_PARENT,
                )
                root.addView(this)
            }
        }
        return childRouters.getOrPut(id) {
            getChildRouter(childContainer).also { childRouter ->
                if (!childRouter.hasRootController()) {
                    childRouter.setRoot(RouterTransaction.with(factory(id)).tag(id.toString()))
                }
                onChildRouterCreated?.invoke(childRouter)
            }
        }
    }

    fun switchTo(@IdRes id: Int) {
        if (view == null) {
            pendingTabId = id
            return
        }
        ensureTab(id) ?: run { pendingTabId = id; return }
        // INVISIBLE (not GONE) for inactive tabs: keeps them measured/laid-out AND attached, so a
        // switch is draw-only (no re-layout hitch, no Coil cover reload/flicker).
        containers.forEach { (tabId, container) ->
            container.visibility = if (tabId == id) View.VISIBLE else View.INVISIBLE
        }
        activeTabId = id
        childRouters[id]?.rebindIfNeeded()
        updateMenuVisibility()
        onActiveTabChanged?.invoke()
    }

    companion object {
        private const val STATE_ACTIVE_TAB = "tab_host_active_tab"
    }
}
