package com.focusguard.baselineprofile

import androidx.benchmark.macro.MacrobenchmarkScope

internal const val TARGET_PACKAGE = "com.focusguard.v2"

/** Common startup journey shared by profile generation and Macrobenchmark. */
internal fun MacrobenchmarkScope.prepareFocusGuardStartup() {
    pressHome()
}

internal fun MacrobenchmarkScope.startFocusGuardAndWait() {
    startActivityAndWait()
}
