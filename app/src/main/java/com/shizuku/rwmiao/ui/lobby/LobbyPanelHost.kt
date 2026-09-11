package com.shizuku.rwmiao.ui.lobby

import android.content.Context
import android.os.Bundle
import android.view.View
import android.view.ViewParent
import android.widget.FrameLayout
import android.widget.ScrollView
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.ComposeView
import androidx.compose.ui.platform.ViewCompositionStrategy
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.runtime.R as LifecycleRuntimeR
import androidx.lifecycle.setViewTreeLifecycleOwner
import androidx.savedstate.SavedStateRegistry
import androidx.savedstate.SavedStateRegistryController
import androidx.savedstate.SavedStateRegistryOwner
import androidx.savedstate.R as SavedStateR
import androidx.savedstate.setViewTreeSavedStateRegistryOwner
import com.shizuku.rwmiao.config.SettingsContract.*
import com.shizuku.rwmiao.module.lobby.LobbyRoom
import com.shizuku.rwmiao.ui.support.moduleComposeContext

class LobbyPanelHost(
    context: Context,
    private val callbacks: Callbacks
) : FrameLayout(context) {
    interface Callbacks {
        fun onRoomClicked(room: LobbyRoom)
        fun onRefreshClicked()
        fun onKeywordsChanged()
    }

    private val owner = ComposeViewTreeOwner()
    private val composeView = ComposeView(moduleComposeContext(context))
    private val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
    private var rooms by mutableStateOf<List<LobbyRoom>>(emptyList())
    private var recentRooms by mutableStateOf(loadLobbyRecentRooms(preferences))
    private var contentInstalled = false
    private var windowContentChild: View? = null
    private var previousWindowLifecycleOwner: LifecycleOwner? = null
    private var previousWindowSavedStateOwner: SavedStateRegistryOwner? = null

    init {
        composeView.setViewCompositionStrategy(
            ViewCompositionStrategy.DisposeOnDetachedFromWindowOrReleasedFromPool
        )
    }

    fun submitRooms(nextRooms: List<LobbyRoom>) {
        val scrollView = findScrollViewParent()
        val scrollX = scrollView?.scrollX ?: 0
        val scrollY = scrollView?.scrollY ?: 0
        rooms = nextRooms.toList()
        if (scrollView != null) {
            scrollView.post {
                scrollView.scrollTo(scrollX, scrollY)
            }
        }
    }

    private fun findScrollViewParent(): ScrollView? {
        var current: ViewParent? = parent
        while (current != null) {
            if (current is ScrollView) return current
            current = current.parent
        }
        return null
    }

    override fun onAttachedToWindow() {
        setViewTreeLifecycleOwner(owner)
        setViewTreeSavedStateRegistryOwner(owner)
        installWindowOwners()
        owner.attach()
        super.onAttachedToWindow()
        if (composeView.parent == null) {
            composeView.setViewTreeLifecycleOwner(owner)
            composeView.setViewTreeSavedStateRegistryOwner(owner)
            addView(
                composeView,
                LayoutParams(
                    maxOf(dp(760), resources.displayMetrics.widthPixels - dp(24)),
                    LayoutParams.WRAP_CONTENT
                )
            )
        }
        if (!contentInstalled) {
            contentInstalled = true
            composeView.setContent {
                LobbyRoomListV6(rooms, recentRooms, callbacks)
            }
        }
    }

    fun markRoomJoined(room: LobbyRoom) {
        val snapshot = RecentLobbyRoomV6.from(room)
        recentRooms = listOf(snapshot) + recentRooms
            .filterNot { it.historyKey == snapshot.historyKey }
            .take(29)
        saveLobbyRecentRooms(preferences, recentRooms)
    }

    override fun onDetachedFromWindow() {
        owner.detach()
        super.onDetachedFromWindow()
        restoreWindowOwners()
    }

    private fun installWindowOwners() {
        val root = findWindowContentChild() ?: return
        if (windowContentChild != null && windowContentChild !== root) {
            restoreWindowOwners()
        }
        if (windowContentChild == null) {
            windowContentChild = root
            previousWindowLifecycleOwner = root.getTag(
                LifecycleRuntimeR.id.view_tree_lifecycle_owner
            ) as? LifecycleOwner
            previousWindowSavedStateOwner = root.getTag(
                SavedStateR.id.view_tree_saved_state_registry_owner
            ) as? SavedStateRegistryOwner
        }
        root.setViewTreeLifecycleOwner(owner)
        root.setViewTreeSavedStateRegistryOwner(owner)
    }

    private fun restoreWindowOwners() {
        val root = windowContentChild ?: return
        root.setTag(LifecycleRuntimeR.id.view_tree_lifecycle_owner, previousWindowLifecycleOwner)
        root.setTag(
            SavedStateR.id.view_tree_saved_state_registry_owner,
            previousWindowSavedStateOwner
        )
        windowContentChild = null
        previousWindowLifecycleOwner = null
        previousWindowSavedStateOwner = null
    }

    private fun findWindowContentChild(): View? {
        var child: View = this
        var parent = child.parent
        while (parent is View) {
            if (parent.id == android.R.id.content) return child
            child = parent
            parent = child.parent
        }
        return null
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density + 0.5f).toInt()
}

private class ComposeViewTreeOwner : SavedStateRegistryOwner {
    private val lifecycleRegistry = LifecycleRegistry(this)
    private val savedStateController = SavedStateRegistryController.create(this)

    init {
        savedStateController.performAttach()
        savedStateController.performRestore(Bundle())
    }

    override val lifecycle: Lifecycle
        get() = lifecycleRegistry

    override val savedStateRegistry: SavedStateRegistry
        get() = savedStateController.savedStateRegistry

    fun attach() {
        if (lifecycleRegistry.currentState == Lifecycle.State.INITIALIZED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_CREATE)
        }
        if (lifecycleRegistry.currentState == Lifecycle.State.CREATED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_START)
        }
        if (lifecycleRegistry.currentState == Lifecycle.State.STARTED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_RESUME)
        }
    }

    fun detach() {
        if (lifecycleRegistry.currentState == Lifecycle.State.RESUMED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_PAUSE)
        }
        if (lifecycleRegistry.currentState == Lifecycle.State.STARTED) {
            lifecycleRegistry.handleLifecycleEvent(Lifecycle.Event.ON_STOP)
        }
    }
}
