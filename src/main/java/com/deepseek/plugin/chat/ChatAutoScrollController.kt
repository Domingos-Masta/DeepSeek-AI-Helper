package com.deepseek.plugin.chat

/** Tracks user scroll intent independently from layout-driven scrollbar changes. */
internal class ChatAutoScrollController(private val bottomThreshold: Int = 8) {
    var followsBottom: Boolean = true
        private set

    fun resumeFollowing() {
        followsBottom = true
    }

    /** User explicitly left the bottom (e.g. scrolled up) — synchronous call, pauses following immediately. */
    fun onUserScrollAway() {
        followsBottom = false
    }

    fun onUserViewportChanged(value: Int, visibleAmount: Int, maximum: Int) {
        followsBottom = value + visibleAmount >= maximum - bottomThreshold
    }
}
