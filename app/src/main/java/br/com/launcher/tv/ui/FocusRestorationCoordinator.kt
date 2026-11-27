package br.com.launcher.tv.ui

import android.view.View
import android.view.ViewGroup

/**
 * Restores only the last explicitly tagged focus target after this Activity
 * regains window focus. It never starts an Activity or changes HOME selection.
 */
class FocusRestorationCoordinator {
    private var rememberedAnchor: String? = null

    fun capture(focused: View?) {
        val anchor = (focused?.tag as? FocusAnchor)?.key
        if (!anchor.isNullOrBlank()) rememberedAnchor = anchor
    }

    fun restore(root: ViewGroup): Boolean {
        val anchor = rememberedAnchor ?: return false
        val target = findTarget(root, anchor) ?: return false
        if (target.hasFocus()) return true
        return target.requestFocus()
    }

    fun rememberedKey(): String? = rememberedAnchor

    private fun findTarget(view: View, anchor: String): View? {
        if ((view.tag as? FocusAnchor)?.key == anchor &&
            view.isShown && view.isFocusable && view.isEnabled
        ) {
            return view
        }

        if (view is ViewGroup) {
            for (index in 0 until view.childCount) {
                val found = findTarget(view.getChildAt(index), anchor)
                if (found != null) return found
            }
        }
        return null
    }
}
