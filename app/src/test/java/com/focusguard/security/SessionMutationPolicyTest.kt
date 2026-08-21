package com.focusguard.security

import com.focusguard.database.BlockSession
import com.focusguard.domain.model.BlockSessionType
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class SessionMutationPolicyTest {

    @Test
    fun `active time session cannot remove protected content`() {
        val session = BlockSession(
            sessionType = BlockSessionType.TIME,
            isActive = true,
            endTime = 20_000L
        )

        assertThat(SessionMutationPolicy.canRemoveProtectedContent(session, 10_000L)).isFalse()
    }

    @Test
    fun `expired time session permits cleanup`() {
        val session = BlockSession(
            sessionType = BlockSessionType.TIME,
            isActive = true,
            endTime = 10_000L
        )

        assertThat(SessionMutationPolicy.canRemoveProtectedContent(session, 10_000L)).isTrue()
    }

    @Test
    fun `password session remains editable after its authenticated flow`() {
        val session = BlockSession(sessionType = BlockSessionType.PASSWORD, isActive = true)

        assertThat(SessionMutationPolicy.canRemoveProtectedContent(session, 10_000L)).isTrue()
    }
}
