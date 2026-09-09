package com.focusguard.baselineprofile

import android.content.ComponentName
import android.content.Intent
import android.view.KeyEvent
import androidx.benchmark.macro.MacrobenchmarkScope
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until

internal const val TARGET_PACKAGE = "com.focusguard.v2"

private const val PERFORMANCE_ACTIVITY = "com.focusguard.performance.PerformanceInputActivity"
private const val PERFORMANCE_MODE_EXTRA = "hardblock.performance.mode"
private const val MODE_USAGE_LIMIT = "usage_limit"
private const val MODE_PASSWORD = "password"
private const val USAGE_MINUTES_TAG = "perf_usage_minutes"
private const val RULE_DURATION_TAG = "perf_rule_duration"
private const val PASSWORD_TAG = "perf_password"
private const val PASSWORD_CONFIRMATION_TAG = "perf_password_confirmation"
private const val UI_TIMEOUT_MS = 5_000L

/** Common startup journey shared by profile generation and Macrobenchmark. */
internal fun MacrobenchmarkScope.prepareFocusGuardStartup() {
    pressHome()
}

internal fun MacrobenchmarkScope.startFocusGuardAndWait() {
    startActivityAndWait()
}

/**
 * Launches an Activity that exists only in profile/benchmark manifests. Production
 * builds never register this fixture, so measurement cannot become a user-facing
 * or blocking-path code route.
 */
private fun MacrobenchmarkScope.startInputFixture(mode: String, readyTag: String) {
    killProcess()
    val intent = Intent(Intent.ACTION_MAIN).apply {
        component = ComponentName(TARGET_PACKAGE, PERFORMANCE_ACTIVITY)
        addCategory(Intent.CATEGORY_LAUNCHER)
        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        putExtra(PERFORMANCE_MODE_EXTRA, mode)
    }
    startActivityAndWait(intent)
    check(device.wait(Until.hasObject(By.res(readyTag)), UI_TIMEOUT_MS)) {
        "Performance fixture did not expose $readyTag"
    }
}

internal fun MacrobenchmarkScope.prepareUsageLimitInputJourney() {
    startInputFixture(MODE_USAGE_LIMIT, USAGE_MINUTES_TAG)
}

internal fun MacrobenchmarkScope.exerciseUsageLimitInputJourney() {
    val minutesField = requireObject(USAGE_MINUTES_TAG)
    minutesField.click()
    pressDigits(1, 2, 0)

    val durationField = requireObject(RULE_DURATION_TAG)
    durationField.click()
    device.pressKeyCode(KeyEvent.KEYCODE_MOVE_END)
    device.pressKeyCode(KeyEvent.KEYCODE_DEL)
    pressDigits(3)

    // Include IME dismissal in the measured/profiled interaction because the
    // original regression included resize/layout work on keyboard open/close.
    device.pressBack()
}

internal fun MacrobenchmarkScope.preparePasswordInputJourney() {
    startInputFixture(MODE_PASSWORD, PASSWORD_TAG)
}

internal fun MacrobenchmarkScope.exercisePasswordInputJourney() {
    val password = requireObject(PASSWORD_TAG)
    password.click()
    pressDigits(1, 2, 3, 4, 5, 6, 7, 8)

    val confirmation = requireObject(PASSWORD_CONFIRMATION_TAG)
    confirmation.click()
    pressDigits(1, 2, 3, 4, 5, 6, 7, 8)
    device.pressBack()
}

private fun MacrobenchmarkScope.requireObject(tag: String) =
    device.findObject(By.res(tag)) ?: error("Could not find Compose test tag $tag")

private fun MacrobenchmarkScope.pressDigits(vararg digits: Int) {
    digits.forEach { digit ->
        val keyCode = when (digit) {
            0 -> KeyEvent.KEYCODE_0
            1 -> KeyEvent.KEYCODE_1
            2 -> KeyEvent.KEYCODE_2
            3 -> KeyEvent.KEYCODE_3
            4 -> KeyEvent.KEYCODE_4
            5 -> KeyEvent.KEYCODE_5
            6 -> KeyEvent.KEYCODE_6
            7 -> KeyEvent.KEYCODE_7
            8 -> KeyEvent.KEYCODE_8
            9 -> KeyEvent.KEYCODE_9
            else -> error("Only decimal digits are supported")
        }
        device.pressKeyCode(keyCode)
    }
}
