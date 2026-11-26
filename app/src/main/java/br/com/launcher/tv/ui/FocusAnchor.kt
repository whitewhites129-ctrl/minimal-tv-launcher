package br.com.launcher.tv.ui

/**
 * Stable identity stored in a View's ordinary tag.
 * appIndex preserves the existing long-press/reorder behavior for app cards.
 */
data class FocusAnchor(
    val key: String,
    val appIndex: Int? = null
)
