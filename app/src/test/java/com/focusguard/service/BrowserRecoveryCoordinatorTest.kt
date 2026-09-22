package com.focusguard.service

import org.junit.Assert.*
import org.junit.Test

class BrowserRecoveryCoordinatorTest {
    private fun token(generation: Long, sequence: Long = generation) =
        BrowserInspectionCoordinator.Token("com.android.chrome", generation.toInt(), generation, sequence)

    private fun sameDocumentToken(sequence: Long, surfaceEpoch: Long = 1L) =
        BrowserInspectionCoordinator.Token(
            "com.android.chrome",
            10,
            1L,
            sequence,
            surfaceEpoch = surfaceEpoch
        )

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
    fun contentStormDoesNotRestartRecoveryDeadlineInSameDocument() {
        val queue = BrowserRecoveryCoordinator()
        val active = sameDocumentToken(1)
        assertTrue(queue.offer(active))

        repeat(30) { assertFalse(queue.offer(sameDocumentToken(it + 2L))) }

        assertTrue(queue.isCurrent(active))
        assertTrue(active.allowSequenceAdvance)
        assertNull(queue.finish(active))
    }

    @Test
    fun newSurfaceEpochCancelsOldRecoveryAndQueuesReplacement() {
        val queue = BrowserRecoveryCoordinator()
        val old = sameDocumentToken(sequence = 1L, surfaceEpoch = 1L)
        val replacement = sameDocumentToken(sequence = 2L, surfaceEpoch = 2L)

        assertTrue(queue.offer(old))
        assertFalse(queue.offer(replacement))
        assertFalse(queue.isCurrent(old))
        assertSame(replacement, queue.finish(old))
        assertTrue(queue.isCurrent(replacement))
    }

    @Test
    fun differentWindowStillCancelsOldRecoveryAndQueuesReplacement() {
        val queue = BrowserRecoveryCoordinator()
        val old = sameDocumentToken(1)
        val replacement = BrowserInspectionCoordinator.Token("com.android.chrome", 11, 2L, 2L)
        assertTrue(queue.offer(old))
        assertFalse(queue.offer(replacement))
        assertFalse(queue.isCurrent(old))
        assertSame(replacement, queue.finish(old))
        assertTrue(queue.isCurrent(replacement))
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
