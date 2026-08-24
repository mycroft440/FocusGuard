package com.focusguard.security

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CurtainDestinationReadyCoordinatorTest {
    @Test
    fun `safe surface readiness stays in process and rejects missing generation`() {
        val received = mutableListOf<Long>()
        val listener = CurtainDestinationReadyCoordinator.Listener { received += it }
        try {
            CurtainDestinationReadyCoordinator.register(listener)

            CurtainDestinationReadyCoordinator.notifyReady(0L)
            CurtainDestinationReadyCoordinator.notifyReady(19L)

            assertThat(received).containsExactly(19L)
        } finally {
            CurtainDestinationReadyCoordinator.unregister(listener)
        }

        CurtainDestinationReadyCoordinator.notifyReady(20L)
        assertThat(received).containsExactly(19L)
    }
}
