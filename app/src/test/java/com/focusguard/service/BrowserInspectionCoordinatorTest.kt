package com.focusguard.service

import com.focusguard.accessibility.website.identification.BrowserObservationSignal
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
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

        val offers = (2L..31L).map { sequence ->
            coordinator.offer("com.android.chrome", 10, 2048, sequence, sequence, "", listOf("$sequence"), null)
        }
        offers.forEach { assertFalse(it.startWorker) }

        val next = coordinator.finishPass()
        assertSame(offers.last().snapshot, next)
        assertTrue(coordinator.isCurrent(offers.last().snapshot.token, requireLatestSequence = true))
        offers.dropLast(1).forEach {
            assertFalse(coordinator.isCurrent(it.snapshot.token, requireLatestSequence = true))
        }
        // A pass that was actually validated against the live root may continue
        // async confirmation/recovery while newer events arrive in this generation.
        assertTrue(coordinator.isCurrent(first.snapshot.token, requireLatestSequence = true))
    }

    @Test
    fun validatedPassMaySurviveNewSequenceButNotNewWindow() {
        val coordinator = BrowserInspectionCoordinator()
        val validated = coordinator.offer("com.android.chrome", 10, 2048, 1L, 1L, "", emptyList(), null)
        coordinator.takePending()
        coordinator.offer("com.android.chrome", 10, 2048, 2L, 2L, "", emptyList(), null)
        coordinator.finishPass()

        assertTrue(coordinator.isCurrent(validated.snapshot.token, requireLatestSequence = true))
        coordinator.observeWindow("com.android.chrome", 11)
        assertFalse(coordinator.isCurrent(validated.snapshot.token, requireLatestSequence = true))
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

    @Test
    fun invalidateDropsPendingWorkAndOldGeneration() {
        val coordinator = BrowserInspectionCoordinator()
        val running = coordinator.offer(
            "com.android.chrome", 10, 2048, 1L, 1L, "", emptyList(), null
        )
        assertSame(running.snapshot, coordinator.takePending())
        coordinator.offer(
            "com.android.chrome", 10, 2048, 2L, 2L, "", listOf("pending"), null
        )

        coordinator.invalidate()

        assertFalse(coordinator.isCurrent(running.snapshot.token))
        assertNull(coordinator.finishPass())
        val replacement = coordinator.offer(
            "com.android.chrome", 11, 2048, 3L, 3L, "", emptyList(), null
        )
        assertTrue(replacement.startWorker)
        assertTrue(coordinator.isCurrent(replacement.snapshot.token, requireLatestSequence = true))
    }

    @Test
    fun offeringInspectionPublishesPrimitiveObservationSignal() {
        val packageName = "test.browser.coordinator"
        val windowId = 91
        BrowserObservationSignal.clearForTest(packageName, windowId)
        val baseline = BrowserObservationSignal.currentVersion(packageName, windowId)
        val coordinator = BrowserInspectionCoordinator()

        coordinator.offer(packageName, windowId, 2048, 1L, 1L, "", emptyList(), null)

        assertTrue(BrowserObservationSignal.currentVersion(packageName, windowId) > baseline)
        BrowserObservationSignal.clearForTest(packageName, windowId)
    }
}
