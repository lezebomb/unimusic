package moe.ouom.neriplayer.ui.navigation

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.layout
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.LifecycleRegistry
import androidx.lifecycle.compose.LocalLifecycleOwner

/** Keep visited tab roots warm without laying out, drawing or collecting UI flows when hidden. */
internal fun Modifier.mainTabSceneVisibility(active: Boolean): Modifier = layout { measurable, constraints ->
    if (active) {
        val placeable = measurable.measure(constraints)
        layout(placeable.width, placeable.height) { placeable.placeRelative(0, 0) }
    } else {
        layout(0, 0) { }
    }
}

@Composable
internal fun RetainedMainTabScene(active: Boolean, content: @Composable () -> Unit) {
    val parent = LocalLifecycleOwner.current
    val owner = remember(parent) { MainTabSceneLifecycleOwner() }
    SideEffect { owner.update(parent.lifecycle.currentState, active) }
    DisposableEffect(parent, owner) {
        val observer = LifecycleEventObserver { _, _ -> owner.updateParent(parent.lifecycle.currentState) }
        parent.lifecycle.addObserver(observer)
        onDispose {
            parent.lifecycle.removeObserver(observer)
            owner.destroy()
        }
    }
    CompositionLocalProvider(LocalLifecycleOwner provides owner, content = content)
}

private class MainTabSceneLifecycleOwner : LifecycleOwner {
    private val registry = LifecycleRegistry(this)
    override val lifecycle: Lifecycle get() = registry
    private var active = false

    fun update(state: Lifecycle.State, active: Boolean) {
        this.active = active
        updateParent(state)
    }

    fun updateParent(state: Lifecycle.State) {
        if (registry.currentState == Lifecycle.State.DESTROYED) return
        registry.currentState = if (active) state else minOf(state, Lifecycle.State.CREATED)
    }

    fun destroy() {
        if (registry.currentState != Lifecycle.State.INITIALIZED) registry.currentState = Lifecycle.State.DESTROYED
    }
}
