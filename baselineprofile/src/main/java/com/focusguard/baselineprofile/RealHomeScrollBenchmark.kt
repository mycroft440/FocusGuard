package com.focusguard.baselineprofile

import androidx.benchmark.macro.BaselineProfileMode
import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Measures the actual protection home screen, rather than the input-only fixture.
 * Run on an idle device with onboarding completed and no active Focus Mode session.
 */
@RunWith(AndroidJUnit4::class)
class RealHomeScrollBenchmark {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun homeScrollFrameTiming() = benchmarkRule.measureRepeated(
        packageName = TARGET_PACKAGE,
        metrics = listOf(FrameTimingMetric()),
        compilationMode = CompilationMode.Partial(
            baselineProfileMode = BaselineProfileMode.Require
        ),
        iterations = 10,
        setupBlock = {
            pressHome()
            startActivityAndWait()
            check(device.wait(Until.hasObject(By.res(HOME_SCROLL_TAG)), 10_000L)) {
                "Home scroll container not available; finish setup and disable Focus Mode."
            }
        },
        measureBlock = {
            val list = device.findObject(By.res(HOME_SCROLL_TAG))
                ?: error("Home scroll container disappeared during benchmark")
            list.swipe(Direction.UP, 0.8f, 600)
            list.swipe(Direction.DOWN, 0.8f, 600)
        }
    )

    private companion object {
        const val HOME_SCROLL_TAG = "focusguard_home_scroll"
    }
}
