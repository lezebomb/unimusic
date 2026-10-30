package moe.ouom.neriplayer.util.platform

import android.app.Activity
import android.content.Context
import android.hardware.display.DisplayManager
import android.os.Build
import android.view.Display
import android.view.View
import android.view.ViewGroup

internal fun resolvePreferredRefreshRate(
    highRefreshRateEnabled: Boolean,
    supportedRefreshRates: Iterable<Float>,
    currentRefreshRate: Float?
): Float {
    if (!highRefreshRateEnabled) return 0f
    return supportedRefreshRates
        .filter { rate -> rate.isFinite() && rate > 0f }
        .maxOrNull()
        ?: currentRefreshRate?.takeIf { rate -> rate.isFinite() && rate > 0f }
        ?: 0f
}

/** The foreground UI keeps its high refresh request until the user disables it or leaves the app. */
internal fun Activity.applyPreferredHighRefreshRate(highRefreshRateEnabled: Boolean) {
    val display = window.decorView.display ?:
        (getSystemService(Context.DISPLAY_SERVICE) as? DisplayManager)
            ?.getDisplay(Display.DEFAULT_DISPLAY)
    val currentMode = display?.mode
    val modes = display?.supportedModes.orEmpty().filter { mode ->
        currentMode == null || (mode.physicalWidth == currentMode.physicalWidth &&
            mode.physicalHeight == currentMode.physicalHeight)
    }
    val preferredRefreshRate = resolvePreferredRefreshRate(
        highRefreshRateEnabled = highRefreshRateEnabled,
        supportedRefreshRates = modes.map { it.refreshRate },
        currentRefreshRate = display?.refreshRate
    )
    // Use a same-resolution mode as well as a rate vote so static Compose content retains the request.
    val preferredModeId = if (highRefreshRateEnabled && preferredRefreshRate > 0f) {
        display?.matchingModeId(preferredRefreshRate) ?: 0
    } else 0
    applyPreferredRefreshRate(preferredRefreshRate, preferredModeId)
    applyRequestedViewFrameRate(preferredRefreshRate.takeIf { highRefreshRateEnabled && it > 0f })
}

private fun Activity.applyRequestedViewFrameRate(frameRate: Float?) {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.VANILLA_ICE_CREAM) return
    val requested = frameRate ?: View.REQUESTED_FRAME_RATE_CATEGORY_DEFAULT
    fun apply(view: View) {
        val current = view.requestedFrameRate
        val unchanged = current == requested || (current.isNaN() && requested.isNaN())
        if (!unchanged) view.requestedFrameRate = requested
        if (view is ViewGroup) {
            for (index in 0 until view.childCount) apply(view.getChildAt(index))
        }
    }
    apply(window.decorView)
}

private fun Display.matchingModeId(refreshRate: Float): Int? {
    val current = mode ?: return null
    return supportedModes.orEmpty().firstOrNull { candidate ->
        candidate.physicalWidth == current.physicalWidth &&
            candidate.physicalHeight == current.physicalHeight &&
            kotlin.math.abs(candidate.refreshRate - refreshRate) < 1f
    }?.modeId
}

private fun Activity.applyPreferredRefreshRate(preferredRefreshRate: Float, preferredModeId: Int) {
    val attributes = window.attributes
    if (attributes.preferredRefreshRate == preferredRefreshRate &&
        attributes.preferredDisplayModeId == preferredModeId
    ) {
        return
    }
    attributes.preferredRefreshRate = preferredRefreshRate
    attributes.preferredDisplayModeId = preferredModeId
    window.attributes = attributes
}
