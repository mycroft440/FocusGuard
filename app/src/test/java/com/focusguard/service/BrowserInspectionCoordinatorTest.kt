package com.focusguard.service

import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

class BrowserInspectionCoordinatorTest {
    @Test
    fun contentStormKeepsOnlyLatestPendingInspection() {
        val coordinator = BrowserInspectionCoordinator()
        val first = coordinator.offer("com.android.chrome", 10, 2048, 1L, 1L, "", emptyList(), null)
        assertTrue(first.startWorker)
        assertSame(first.snapshot, coordinator.takePending())

        val second = coordinator.offer("com.android.chrome", 10, 2048, 2L, 2L, "", listOf("a"), null)
        val third = coordinator.offer("com.android.chrome", 10, 2048, 3L, 3L, "", listOf("b"), null)
        assertFalse(second.startWorker)
        assertFalse(third.startWorker)

        val next = coordinator.finishPass()
        assertSame(third.snapshot, next)
        assertTrue(coordinator.isCurrent(third.snapshot.token, requireLatestSequence = true))
        assertFalse(coordinator.isCurrent(second.snapshot.token, requireLatestSequence = true))
    }

    @Test
    fun changingWindowInvalidatesOldGeneration() {
        val coordinator = BrowserInspectionCoordinator()
        val old = coordinator.offer("com.android.chrome", 10, 32, 1L, 1L, "", emptyList(), null)
        coordinator.takePending()
        val oldGeneration = old.snapshot.token.generation

        coordinator.observeWindow("com.android.chrome", 11)

        assertFalse(coordinator.isCurrent(old.snapshot.token))
        assertFalse(coordinator.isCurrentWindow("com.android.chrome", 10, oldGeneration))
        val replacement = coordinator.offer("com.android.chrome", 11, 32, 2L, 2L, "", emptyList(), null)
        assertNotEquals(oldGeneration, replacement.snapshot.token.generation)
        assertTrue(coordinator.isCurrent(replacement.snapshot.token))
    }

    @Test
    fun switchingPackageInvalidatesOldWindowEvenWhenWindowIdIsReused() {
        val coordinator = BrowserInspectionCoordinator()
        val old = coordinator.offer("com.android.chrome", 7, 32, 1L, 1L, "", emptyList(), null)
        coordinator.takePending()

        coordinator.observeWindow("com.microsoft.emmx", 7)

        assertFalse(coordinator.isCurrent(old.snapshot.token))
    }
}
