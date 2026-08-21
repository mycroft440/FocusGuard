package com.focusguard.database

import com.focusguard.domain.model.BlockSessionType
import com.focusguard.domain.model.UsageLimitLockMode
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class DatabaseConvertersTest {

    private val converters = DatabaseConverters()

    @Test
    fun `closed database types round trip through their canonical names`() {
        BlockSessionType.entries.forEach { type ->
            assertThat(
                converters.blockSessionTypeFromStorage(
                    converters.blockSessionTypeToStorage(type)
                )
            ).isEqualTo(type)
        }
        UsageLimitLockMode.entries.forEach { mode ->
            assertThat(
                converters.usageLimitLockModeFromStorage(
                    converters.usageLimitLockModeToStorage(mode)
                )
            ).isEqualTo(mode)
        }
    }

    @Test
    fun `legacy casing is accepted but unknown enum values fail closed`() {
        assertThat(converters.blockSessionTypeFromStorage("pomodoro"))
            .isEqualTo(BlockSessionType.POMODORO)
        assertThat(converters.usageLimitLockModeFromStorage("password"))
            .isEqualTo(UsageLimitLockMode.PASSWORD)

        assertThat(
            runCatching { converters.blockSessionTypeFromStorage("unknown") }
                .exceptionOrNull()
        ).isInstanceOf(IllegalArgumentException::class.java)
        assertThat(
            runCatching { converters.usageLimitLockModeFromStorage("warning") }
                .exceptionOrNull()
        ).isInstanceOf(IllegalArgumentException::class.java)
    }

    @Test
    fun `recurring weekdays are canonicalized and invalid values are discarded`() {
        val stored = converters.recurringDaysToStorage(setOf(7, 2, 9, 1, 0))

        assertThat(stored).isEqualTo("1,2,7")
        assertThat(converters.recurringDaysFromStorage("7, 2,invalid,2,9,1"))
            .containsExactly(7, 2, 1)
    }
}
