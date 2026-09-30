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

    @Test
    fun `only a frame-committed acknowledgement marks its generation as committed`() {
        CurtainDestinationReadyCoordinator.notifyReady(30L)
        assertThat(CurtainDestinationReadyCoordinator.isFrameCommitted(30L)).isFalse()

        CurtainDestinationReadyCoordinator.notifyReady(31L, frameCommitted = true)
        assertThat(CurtainDestinationReadyCoordinator.isFrameCommitted(31L)).isTrue()
        assertThat(CurtainDestinationReadyCoordinator.isFrameCommitted(30L)).isFalse()
        assertThat(CurtainDestinationReadyCoordinator.isFrameCommitted(0L)).isFalse()
    }

    @Test
    fun `curtain hidden reaches only the registered destination`() {
        val hidden = mutableListOf<Long>()
        val listener = CurtainDestinationReadyCoordinator.CurtainHiddenListener { hidden += it }
        CurtainDestinationReadyCoordinator.setCurtainHiddenListener(listener)
        try {
            CurtainDestinationReadyCoordinator.notifyCurtainHidden(0L)
            CurtainDestinationReadyCoordinator.notifyCurtainHidden(40L)
            assertThat(hidden).containsExactly(40L)
        } finally {
            CurtainDestinationReadyCoordinator.clearCurtainHiddenListener(listener)
        }
        CurtainDestinationReadyCoordinator.notifyCurtainHidden(41L)
        assertThat(hidden).containsExactly(40L)
    }
}
