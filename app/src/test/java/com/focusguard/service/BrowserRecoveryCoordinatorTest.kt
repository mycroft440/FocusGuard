package com.focusguard.service

import org.junit.Assert.*
import org.junit.Test

class BrowserRecoveryCoordinatorTest {
    private fun token(generation: Long, sequence: Long = generation) =
        BrowserInspectionCoordinator.Token("com.android.chrome", generation.toInt(), generation, sequence)

    @Test
    fun newGenerationWaitsForOldRecoveryButIsNeverLost() {
        val queue = BrowserRecoveryCoordinator()
        val old = token(1)
        val new = token(2)
        assertTrue(queue.offer(old))
        assertFalse(queue.offer(new))
        assertFalse(queue.isCurrent(old))
        assertEquals(new, queue.finish(old))
        assertTrue(queue.isCurrent(new))
        assertNull(queue.finish(new))
        assertTrue(queue.offer(token(3)))
    }

    @Test
    fun contentStormCoalescesRecoveryAndOldCleanupCannotCancelReplacement() {
        val queue = BrowserRecoveryCoordinator()
        val old = token(1, 1)
        queue.offer(old)
        repeat(30) { assertFalse(queue.offer(token(1, it + 2L))) }
        val latest = token(1, 31)
        assertEquals(latest, queue.finish(old))
        assertNull(queue.finish(old))
        assertTrue(queue.isCurrent(latest))
    }

    @Test
    fun identifiedPageCancelsPendingRecoveryWithoutReleasingTheHeavySlot() {
        val queue = BrowserRecoveryCoordinator()
        val old = token(1)
        queue.offer(old)
        queue.offer(token(2))
        queue.cancel(old.packageName)
        assertFalse(queue.isCurrent(old))
        assertFalse(queue.offer(token(3)))
        assertEquals(token(3), queue.finish(old))
    }
}
