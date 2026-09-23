package com.focusguard.ui

import com.focusguard.manager.BlockingSessionManager.ProtectionKind
import com.focusguard.ui.compose.screens.TimeBlockConfigMode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class CreateSessionProtectionKindTest {

    @Test
    fun `wizard maps each entry point to the kind it creates`() {
        assertThat(protectionKindFor("PASSWORD", TimeBlockConfigMode.CONTINUOUS))
            .isEqualTo(ProtectionKind.PASSWORD)
        assertThat(protectionKindFor("TIME", TimeBlockConfigMode.DAILY_PERIODS))
            .isEqualTo(ProtectionKind.DAILY_PERIODS)
        assertThat(protectionKindFor("TIME", TimeBlockConfigMode.CONTINUOUS))
            .isEqualTo(ProtectionKind.DOPAMINE_FAST)
        assertThat(protectionKindFor("POMODORO", TimeBlockConfigMode.CONTINUOUS)).isNull()
    }
}
