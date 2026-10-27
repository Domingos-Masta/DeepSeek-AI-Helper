package com.deepseek.plugin.chat

import kotlin.test.Test
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ChatAutoScrollControllerTest {
    @Test
    fun `only user viewport changes disable bottom following`() {
        val controller = ChatAutoScrollController()

        assertTrue(controller.followsBottom)
        controller.onUserViewportChanged(value = 100, visibleAmount = 200, maximum = 1_000)

        assertFalse(controller.followsBottom)
    }

    @Test
    fun `user scroll away disables following`() {
        val controller = ChatAutoScrollController()

        assertTrue(controller.followsBottom)
        controller.onUserScrollAway()
        assertFalse(controller.followsBottom)
    }

    @Test
    fun `new message and returning to bottom resume following`() {
        val controller = ChatAutoScrollController()
        controller.onUserViewportChanged(value = 100, visibleAmount = 200, maximum = 1_000)

        controller.resumeFollowing()
        assertTrue(controller.followsBottom)

        // 8px threshold: 792 + 200 = 992 >= 1000 - 8
        controller.onUserViewportChanged(value = 792, visibleAmount = 200, maximum = 1_000)
        assertTrue(controller.followsBottom)

        // Boundary: 791 + 200 = 991 < 992 → left the bottom (holds only with threshold 8; with threshold 50, 991 >= 950 would still be true)
        controller.onUserViewportChanged(value = 791, visibleAmount = 200, maximum = 1_000)
        assertFalse(controller.followsBottom)
    }
}
