package com.focusguard.security

/** Pure handshake decision shared by already-rendered and cold safe Activities. */
object SafeSurfaceReadinessPolicy {
    enum class Decision { ACK_NOW, WAIT_FOR_PRE_DRAW }

    fun decide(
        alreadyDrawn: Boolean,
        freshFrameAfterRequest: Boolean,
        lifecycleResumed: Boolean,
        decorShown: Boolean,
        windowFocused: Boolean
    ): Decision = if (lifecycleResumed && decorShown && (
            (alreadyDrawn && windowFocused) || freshFrameAfterRequest
        )
    ) {
        Decision.ACK_NOW
    } else {
        Decision.WAIT_FOR_PRE_DRAW
    }
}
