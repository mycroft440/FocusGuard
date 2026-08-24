package com.focusguard.security

import com.focusguard.security.SafeSurfaceReadinessPolicy.Decision
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SafeSurfaceReadinessPolicyTest {
    @Test
    fun `already drawn surface acknowledges immediately`() {
        assertThat(
            SafeSurfaceReadinessPolicy.decide(
                alreadyDrawn = true,
                freshFrameAfterRequest = false,
                lifecycleResumed = true,
                decorShown = true,
                windowFocused = true
            )
        )
            .isEqualTo(Decision.ACK_NOW)
    }

    @Test
    fun `cold surface waits for pre draw`() {
        assertThat(
            SafeSurfaceReadinessPolicy.decide(
                alreadyDrawn = false,
                freshFrameAfterRequest = false,
                lifecycleResumed = true,
                decorShown = true,
                windowFocused = true
            )
        )
            .isEqualTo(Decision.WAIT_FOR_PRE_DRAW)
    }

    @Test
    fun `drawn but background surface waits until it is presented again`() {
        assertThat(
            SafeSurfaceReadinessPolicy.decide(
                alreadyDrawn = true,
                freshFrameAfterRequest = false,
                lifecycleResumed = false,
                decorShown = false,
                windowFocused = false
            )
        ).isEqualTo(Decision.WAIT_FOR_PRE_DRAW)
        assertThat(
            SafeSurfaceReadinessPolicy.decide(
                alreadyDrawn = true,
                freshFrameAfterRequest = false,
                lifecycleResumed = true,
                decorShown = true,
                windowFocused = false
            )
        ).isEqualTo(Decision.WAIT_FOR_PRE_DRAW)
    }

    @Test
    fun `fresh frame acknowledges a resumed own-dialog surface without window focus`() {
        assertThat(
            SafeSurfaceReadinessPolicy.decide(
                alreadyDrawn = true,
                freshFrameAfterRequest = true,
                lifecycleResumed = true,
                decorShown = true,
                windowFocused = false
            )
        ).isEqualTo(Decision.ACK_NOW)

        assertThat(
            SafeSurfaceReadinessPolicy.decide(
                alreadyDrawn = true,
                freshFrameAfterRequest = true,
                lifecycleResumed = false,
                decorShown = true,
                windowFocused = false
            )
        ).isEqualTo(Decision.WAIT_FOR_PRE_DRAW)
    }
}
