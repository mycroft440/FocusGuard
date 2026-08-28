from pathlib import Path
import re

ROOT = Path('.')


def read(path: str) -> str:
    return (ROOT / path).read_text(encoding='utf-8')


def write(path: str, text: str) -> None:
    target = ROOT / path
    target.parent.mkdir(parents=True, exist_ok=True)
    target.write_text(text, encoding='utf-8')


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f'{label}: expected exactly one match, found {count}')
    return text.replace(old, new, 1)


# -----------------------------------------------------------------------------
# 1) Power-menu classifier: ambiguous ActionsDialog needs two independent clues.
# -----------------------------------------------------------------------------
policy_path = 'app/src/main/java/com/focusguard/security/PowerMenuProtectionPolicy.kt'
policy = read(policy_path)
old_policy_block = '''    fun isPowerMenu(
        packageName: String,
        className: String,
        values: Iterable<CharSequence?>
    ): Boolean {
        if (!isSystemUiPackage(packageName)) return false
        val rendered = values.toList()
        val hasPowerOff = matchesAction(Action.POWER_OFF, rendered)
        val hasRestart = matchesAction(Action.RESTART, rendered)
        val hasEmergency = matchesAction(Action.EMERGENCY, rendered)
        val knownClass = classMarkers.any { className.contains(it, ignoreCase = true) }
        return when {
            knownClass -> hasPowerOff || hasRestart
            hasPowerOff && hasRestart -> true
            hasPowerOff && hasEmergency -> true
            hasRestart && hasEmergency -> true
            else -> false
        }
    }
'''
new_policy_block = '''    fun isPowerMenu(
        packageName: String,
        className: String,
        values: Iterable<CharSequence?>
    ): Boolean {
        if (!isSystemUiPackage(packageName)) return false
        val rendered = values.toList()
        val hasPowerOff = matchesAction(Action.POWER_OFF, rendered)
        val hasRestart = matchesAction(Action.RESTART, rendered)
        val hasEmergency = matchesAction(Action.EMERGENCY, rendered)
        val specificClass = specificClassMarkers.any {
            className.contains(it, ignoreCase = true)
        }
        val ambiguousClass = ambiguousClassMarkers.any {
            className.contains(it, ignoreCase = true)
        }
        val evidenceCount = listOf(hasPowerOff, hasRestart, hasEmergency).count { it }

        return when {
            // Explicit OEM/AOSP global-actions classes are already a strong signal.
            specificClass -> hasPowerOff || hasRestart
            // ActionsDialog is reused by SystemUI. One word such as “Restart” is
            // not enough: require two independent power-menu actions.
            ambiguousClass -> evidenceCount >= 2 && (hasPowerOff || hasRestart)
            hasPowerOff && hasRestart -> true
            hasPowerOff && hasEmergency -> true
            hasRestart && hasEmergency -> true
            else -> false
        }
    }
'''
policy = replace_once(policy, old_policy_block, new_policy_block, 'power policy')
write(policy_path, policy)

policy_test_path = 'app/src/test/java/com/focusguard/security/PowerMenuProtectionPolicyTest.kt'
policy_test = read(policy_test_path)
needle = '''        assertThat(
            PowerMenuProtectionPolicy.classifyDirect(
                packageName = "com.android.systemui",
                className = "com.android.systemui.ActionsDialog",
                values = listOf("Wi-Fi", "Bluetooth")
            )
        ).isEqualTo(DirectDecision.UNKNOWN)
'''
replacement = needle + '''        assertThat(
            PowerMenuProtectionPolicy.classifyDirect(
                packageName = "com.android.systemui",
                className = "com.android.systemui.ActionsDialog",
                values = listOf("Reiniciar")
            )
        ).isEqualTo(DirectDecision.UNKNOWN)
'''
policy_test = replace_once(policy_test, needle, replacement, 'ambiguous dialog test')
write(policy_test_path, policy_test)


# -----------------------------------------------------------------------------
# 2) Protected power menu: never synthesize HOME. Keep shield across screen-off.
# -----------------------------------------------------------------------------
controller_path = 'app/src/main/java/com/focusguard/service/ProtectedPowerMenuController.kt'
controller = read(controller_path)
controller = replace_once(
    controller,
    '''    internal enum class CloseStage { NONE, BACK_REQUESTED, HOME_REQUESTED }
    internal enum class RecheckDecision { HIDE, KEEP_CHECKING, REQUEST_BACK, REQUEST_HOME }
    internal enum class PowerMatchOverlayDecision {
        PASS,
        SHIELD_AND_CONSUME,
        REQUEST_HOME_FALLBACK
    }
''',
    '''    internal enum class CloseStage { NONE, BACK_REQUESTED }
    internal enum class RecheckDecision { HIDE, KEEP_CHECKING, REQUEST_BACK }
    internal enum class PowerMatchOverlayDecision {
        PASS,
        SHIELD_AND_CONSUME,
        REQUEST_BACK_FALLBACK
    }
''',
    'power enums'
)
controller = replace_once(
    controller,
    '''    private var closeStage = CloseStage.NONE
    private var closeStageAtElapsed = 0L
    private var homeFallbackAttempted = false
    private var recheckScheduled = false
    private var protectionActive = false
''',
    '''    private var closeStage = CloseStage.NONE
    private var closeStageAtElapsed = 0L
    private var closeBackAttempts = 0
    private var recheckScheduled = false
    private var protectionActive = false
    private var screenOff = false
''',
    'power state fields'
)
old_screen = '''    /**
     * Screen-off must never synthesize HOME. In Focus Mode HOME resolves to the
     * Hard Block shell itself, which can look like the app opened spontaneously.
     * A later real global-actions event will recreate the shield if still needed.
     */
    fun onScreenOff() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(::onScreenOff)
            return
        }
        if (overlayVisible) dismiss()
    }
'''
new_screen = '''    /**
     * Screen-off never performs BACK/HOME and never uncovers a power menu that
     * might survive the display transition. The overlay stays attached/visible,
     * while rechecks are paused until SCREEN_ON. This avoids both spontaneous
     * launcher navigation and a protection gap on OEMs that preserve Global Actions.
     */
    fun onScreenOff() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(::onScreenOff)
            return
        }
        screenOff = true
        mainHandler.removeCallbacks(recheckRunnable)
        recheckScheduled = false
        if (!shouldKeepPowerOverlayOnScreenOff(overlayVisible)) {
            dismiss()
        }
    }

    fun onScreenOn() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(::onScreenOn)
            return
        }
        screenOff = false
        if (shouldRecheckPowerOverlayOnScreenOn(overlayVisible)) {
            scheduleRecheck()
        }
    }
'''
controller = replace_once(controller, old_screen, new_screen, 'screen state behavior')
controller = replace_once(
    controller,
    '''            closeStage = CloseStage.NONE
            closeStageAtElapsed = 0L
            homeFallbackAttempted = false
            true
''',
    '''            closeStage = CloseStage.NONE
            closeStageAtElapsed = 0L
            closeBackAttempts = 0
            true
''',
    'show state reset'
)
old_fallback = '''    private fun protectMatchedPowerMenu(): Boolean = when (
        powerMatchOverlayDecision(
            powerMatched = true,
            overlayShown = show()
        )
    ) {
        PowerMatchOverlayDecision.SHIELD_AND_CONSUME -> {
            scheduleRecheck()
            true
        }
        PowerMatchOverlayDecision.REQUEST_HOME_FALLBACK -> {
            requestUnshieldedHomeFallback()
            false
        }
        PowerMatchOverlayDecision.PASS -> false
    }

    private fun requestUnshieldedHomeFallback() {
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        runCatching {
            service.startActivity(
                Intent(Intent.ACTION_MAIN).apply {
                    addCategory(Intent.CATEGORY_HOME)
                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                }
            )
        }.onFailure { error ->
            FocusGuardLogger.logError(
                "PowerMenu",
                "Falha no fechamento HOME sem overlay do menu de energia",
                error
            )
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        }
    }
'''
new_fallback = '''    private fun protectMatchedPowerMenu(): Boolean = when (
        powerMatchOverlayDecision(
            powerMatched = true,
            overlayShown = show()
        )
    ) {
        PowerMatchOverlayDecision.SHIELD_AND_CONSUME -> {
            scheduleRecheck()
            true
        }
        PowerMatchOverlayDecision.REQUEST_BACK_FALLBACK -> {
            // A positively identified native power menu may be closed with BACK,
            // but never with HOME. In Focus Mode HOME is HardBlock itself.
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
            false
        }
        PowerMatchOverlayDecision.PASS -> false
    }
'''
controller = replace_once(controller, old_fallback, new_fallback, 'overlay failure fallback')
controller = controller.replace('        homeFallbackAttempted = false\n', '        closeBackAttempts = 0\n')
controller = controller.replace('        protectionActive = false\n        release()\n', '        protectionActive = false\n        screenOff = false\n        release()\n', 1)
controller = replace_once(
    controller,
    '''    private fun scheduleRecheck() {
        if (!shouldScheduleRecheck(recheckScheduled)) return
        recheckScheduled = true
        mainHandler.postDelayed(recheckRunnable, RECHECK_DELAY_MILLIS)
    }
''',
    '''    private fun scheduleRecheck() {
        if (!shouldScheduleRecheck(recheckScheduled, screenOff)) return
        recheckScheduled = true
        mainHandler.postDelayed(recheckRunnable, RECHECK_DELAY_MILLIS)
    }
''',
    'schedule recheck'
)
start = controller.index('    private val recheckRunnable = Runnable {')
end = controller.index('    private fun resetDetectionState() {', start)
new_recheck_and_close = '''    private val recheckRunnable = Runnable {
        recheckScheduled = false
        if (screenOff) return@Runnable

        val root = findPowerMenuRoot()
        val nowElapsed = SystemClock.elapsedRealtime()
        if (root != null) reliableWindowObserved = true
        val directWindowStillPresent = directSignalActive &&
            directMatchedWindowId >= 0 &&
            service.windows.any { it.id == directMatchedWindowId }
        val undefinedWindowGraceActive = directSignalActive &&
            directMatchedWindowId < 0 &&
            nowElapsed - directSignalAtElapsed <= UNDEFINED_WINDOW_GRACE_MILLIS
        val presence = when {
            root != null || directWindowStillPresent -> PowerMenuPresence.PRESENT
            reliableWindowObserved -> PowerMenuPresence.ABSENT_CONFIRMED
            else -> PowerMenuPresence.UNKNOWN
        }
        when (recheckDecision(
            overlayVisible = overlayVisible,
            presence = presence,
            visibleForMillis = (nowElapsed - overlayShownAtElapsed).coerceAtLeast(0L),
            closeStage = closeStage,
            closeStageForMillis = (nowElapsed - closeStageAtElapsed).coerceAtLeast(0L),
            closeBackAttempts = closeBackAttempts,
            unconfirmedSignalGraceExpired = directSignalActive &&
                directMatchedWindowId < 0 &&
                !undefinedWindowGraceActive
        )) {
            RecheckDecision.HIDE -> dismiss()
            RecheckDecision.KEEP_CHECKING -> scheduleRecheck()
            RecheckDecision.REQUEST_BACK -> requestNativeBackClose()
        }
        recycleSafely(root)
    }

    private fun requestExplicitCancelClose() {
        if (!overlayVisible) return
        requestNativeBackClose()
    }

    private fun requestNativeBackClose() {
        if (!overlayVisible) return
        closeStage = CloseStage.BACK_REQUESTED
        closeStageAtElapsed = SystemClock.elapsedRealtime()
        closeBackAttempts += 1
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        scheduleRecheck()
    }

'''
controller = controller[:start] + new_recheck_and_close + controller[end:]
controller = replace_once(
    controller,
    '''        closeStage = CloseStage.NONE
        closeStageAtElapsed = 0L
        closeBackAttempts = 0
    }
''',
    '''        closeStage = CloseStage.NONE
        closeStageAtElapsed = 0L
        closeBackAttempts = 0
        screenOff = false
    }
''',
    'detection state reset'
)
companion_start = controller.index('    companion object {')
class_end = controller.rfind('\n}')
new_companion = '''    companion object {
        internal fun powerMatchOverlayDecision(
            powerMatched: Boolean,
            overlayShown: Boolean
        ): PowerMatchOverlayDecision = when {
            !powerMatched -> PowerMatchOverlayDecision.PASS
            overlayShown -> PowerMatchOverlayDecision.SHIELD_AND_CONSUME
            else -> PowerMatchOverlayDecision.REQUEST_BACK_FALLBACK
        }

        internal fun recheckDecision(
            overlayVisible: Boolean,
            presence: PowerMenuPresence,
            visibleForMillis: Long,
            closeStage: CloseStage,
            closeStageForMillis: Long,
            closeBackAttempts: Int = 0,
            unconfirmedSignalGraceExpired: Boolean
        ): RecheckDecision = when {
            !overlayVisible -> RecheckDecision.HIDE
            presence == PowerMenuPresence.ABSENT_CONFIRMED -> RecheckDecision.HIDE
            // A class-only/undefined-window signal that never became a real
            // SystemUI power-menu root is a false-positive candidate. Hide it
            // without injecting navigation into the foreground app.
            unconfirmedSignalGraceExpired -> RecheckDecision.HIDE
            closeStage == CloseStage.BACK_REQUESTED &&
                closeStageForMillis >= BACK_RETRY_MILLIS &&
                presence == PowerMenuPresence.PRESENT &&
                closeBackAttempts < MAX_AUTOMATIC_BACK_ATTEMPTS -> RecheckDecision.REQUEST_BACK
            closeStage == CloseStage.BACK_REQUESTED &&
                closeStageForMillis >= BACK_UNKNOWN_GIVE_UP_MILLIS &&
                presence == PowerMenuPresence.UNKNOWN -> RecheckDecision.HIDE
            closeStage != CloseStage.NONE -> RecheckDecision.KEEP_CHECKING
            visibleForMillis >= MAX_OVERLAY_VISIBLE_MILLIS &&
                presence == PowerMenuPresence.PRESENT -> RecheckDecision.REQUEST_BACK
            visibleForMillis >= MAX_OVERLAY_VISIBLE_MILLIS &&
                presence == PowerMenuPresence.UNKNOWN -> RecheckDecision.HIDE
            else -> RecheckDecision.KEEP_CHECKING
        }

        internal fun hiddenFlags(flags: Int): Int =
            flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

        internal fun visibleFlags(flags: Int): Int =
            (flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv()) or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

        internal fun shouldScheduleRecheck(
            alreadyScheduled: Boolean,
            screenOff: Boolean = false
        ): Boolean = !alreadyScheduled && !screenOff

        internal fun shouldConsumeExternalWindowEvent(): Boolean = false

        internal fun shouldRecheckAfterFeedbackInterrupt(
            overlayVisible: Boolean
        ): Boolean = overlayVisible

        internal fun shouldKeepPowerOverlayOnScreenOff(
            overlayVisible: Boolean
        ): Boolean = overlayVisible

        internal fun shouldRecheckPowerOverlayOnScreenOn(
            overlayVisible: Boolean
        ): Boolean = overlayVisible

        internal fun shouldInspectExactPowerWindow(
            packageName: String,
            relevantEvent: Boolean
        ): Boolean = relevantEvent && packageName.isBlank()

        const val MAX_PARENT_DEPTH = 5
        const val MAX_TREE_DEPTH = 12
        const val MAX_CHILDREN_PER_NODE = 30
        const val MAX_TEXT_VALUES = 300
        const val RECHECK_DELAY_MILLIS = 350L
        const val UNDEFINED_WINDOW_GRACE_MILLIS = 1_050L
        const val BACK_RETRY_MILLIS = 1_050L
        const val BACK_UNKNOWN_GIVE_UP_MILLIS = 2_100L
        const val MAX_AUTOMATIC_BACK_ATTEMPTS = 2
        const val MAX_OVERLAY_VISIBLE_MILLIS = 30_000L
    }
'''
controller = controller[:companion_start] + new_companion + controller[class_end:]
write(controller_path, controller)

# Screen receiver must explicitly resume power-menu verification on SCREEN_ON.
service_path = 'app/src/main/java/com/focusguard/service/BlockingAccessibilityService.kt'
service = read(service_path)
old_receiver = '''    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == Intent.ACTION_SCREEN_OFF) {
                foregroundPackageName = null
                stopWebsiteTracking()
                protectedPowerMenuController?.onScreenOff()
                when (screenOffCurtainDecision(
                    curtainVisible = instantBlockCurtainVisible,
                    awaitingSafeSurfaceGeneration = awaitingSafeSurfaceGeneration,
                    unsafeWindowVisible = instantBlockCurtainVisible &&
                        hasUnsafeVisibleWindow()
                )) {
                    InstantCurtainFailsafeDecision.NO_ACTION -> Unit
                    InstantCurtainFailsafeDecision.HIDE -> dismissInstantBlockCurtain()
                    InstantCurtainFailsafeDecision.EVACUATE_THEN_HIDE ->
                        beginCurtainEvacuationBeforeHide()
                }
            }
        }
    }
'''
new_receiver = '''    private val screenStateReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            when (intent?.action) {
                Intent.ACTION_SCREEN_OFF -> {
                    foregroundPackageName = null
                    stopWebsiteTracking()
                    protectedPowerMenuController?.onScreenOff()
                    when (screenOffCurtainDecision(
                        curtainVisible = instantBlockCurtainVisible,
                        awaitingSafeSurfaceGeneration = awaitingSafeSurfaceGeneration,
                        unsafeWindowVisible = instantBlockCurtainVisible &&
                            hasUnsafeVisibleWindow()
                    )) {
                        InstantCurtainFailsafeDecision.NO_ACTION -> Unit
                        InstantCurtainFailsafeDecision.HIDE -> dismissInstantBlockCurtain()
                        InstantCurtainFailsafeDecision.EVACUATE_THEN_HIDE ->
                            beginCurtainEvacuationBeforeHide()
                    }
                }
                Intent.ACTION_SCREEN_ON -> protectedPowerMenuController?.onScreenOn()
            }
        }
    }
'''
service = replace_once(service, old_receiver, new_receiver, 'screen receiver')
service = replace_once(
    service,
    '''    private fun registerScreenStateReceiver() {
        val filter = IntentFilter(Intent.ACTION_SCREEN_OFF)
''',
    '''    private fun registerScreenStateReceiver() {
        val filter = IntentFilter().apply {
            addAction(Intent.ACTION_SCREEN_OFF)
            addAction(Intent.ACTION_SCREEN_ON)
        }
''',
    'screen receiver filter'
)
write(service_path, service)

# Replace stale overlay unit tests with tests for the behavior that is actually used.
overlay_test_path = 'app/src/test/java/com/focusguard/service/OverlayWindowStateTest.kt'
overlay_test = '''package com.focusguard.service

import android.view.WindowManager
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class OverlayWindowStateTest {

    @Test
    fun `hidden overlays are inert and visible overlays remain touchable without key focus`() {
        val base = WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN
        val hiddenCurtain = BlockingAccessibilityService.hiddenOverlayFlags(base)
        val visibleCurtain = BlockingAccessibilityService.visibleOverlayFlags(hiddenCurtain)
        val hiddenPower = ProtectedPowerMenuController.hiddenFlags(base)
        val visiblePower = ProtectedPowerMenuController.visibleFlags(hiddenPower)

        assertThat(hiddenCurtain and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE).isNotEqualTo(0)
        assertThat(hiddenCurtain and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE).isNotEqualTo(0)
        assertThat(visibleCurtain and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE).isEqualTo(0)
        assertThat(visibleCurtain and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE).isNotEqualTo(0)
        assertThat(hiddenPower and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE).isNotEqualTo(0)
        assertThat(visiblePower and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE).isEqualTo(0)
        assertThat(visiblePower and WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE).isNotEqualTo(0)
    }

    @Test
    fun `confirmed absence hides while a present menu stays shielded`() {
        assertThat(
            ProtectedPowerMenuController.recheckDecision(
                overlayVisible = true,
                presence = ProtectedPowerMenuController.PowerMenuPresence.PRESENT,
                visibleForMillis = 350L,
                closeStage = ProtectedPowerMenuController.CloseStage.NONE,
                closeStageForMillis = 0L,
                unconfirmedSignalGraceExpired = false
            )
        ).isEqualTo(ProtectedPowerMenuController.RecheckDecision.KEEP_CHECKING)
        assertThat(
            ProtectedPowerMenuController.recheckDecision(
                overlayVisible = true,
                presence = ProtectedPowerMenuController.PowerMenuPresence.ABSENT_CONFIRMED,
                visibleForMillis = 700L,
                closeStage = ProtectedPowerMenuController.CloseStage.NONE,
                closeStageForMillis = 0L,
                unconfirmedSignalGraceExpired = false
            )
        ).isEqualTo(ProtectedPowerMenuController.RecheckDecision.HIDE)
    }

    @Test
    fun `undefined false positive hides without any navigation`() {
        assertThat(
            ProtectedPowerMenuController.recheckDecision(
                overlayVisible = true,
                presence = ProtectedPowerMenuController.PowerMenuPresence.UNKNOWN,
                visibleForMillis = ProtectedPowerMenuController.UNDEFINED_WINDOW_GRACE_MILLIS,
                closeStage = ProtectedPowerMenuController.CloseStage.NONE,
                closeStageForMillis = 0L,
                unconfirmedSignalGraceExpired = true
            )
        ).isEqualTo(ProtectedPowerMenuController.RecheckDecision.HIDE)
    }

    @Test
    fun `automatic close may retry back but never escalates to home`() {
        assertThat(
            ProtectedPowerMenuController.recheckDecision(
                overlayVisible = true,
                presence = ProtectedPowerMenuController.PowerMenuPresence.PRESENT,
                visibleForMillis = 5_000L,
                closeStage = ProtectedPowerMenuController.CloseStage.BACK_REQUESTED,
                closeStageForMillis = ProtectedPowerMenuController.BACK_RETRY_MILLIS,
                closeBackAttempts = 1,
                unconfirmedSignalGraceExpired = false
            )
        ).isEqualTo(ProtectedPowerMenuController.RecheckDecision.REQUEST_BACK)
        assertThat(
            ProtectedPowerMenuController.recheckDecision(
                overlayVisible = true,
                presence = ProtectedPowerMenuController.PowerMenuPresence.PRESENT,
                visibleForMillis = 7_000L,
                closeStage = ProtectedPowerMenuController.CloseStage.BACK_REQUESTED,
                closeStageForMillis = 4_000L,
                closeBackAttempts = ProtectedPowerMenuController.MAX_AUTOMATIC_BACK_ATTEMPTS,
                unconfirmedSignalGraceExpired = false
            )
        ).isEqualTo(ProtectedPowerMenuController.RecheckDecision.KEEP_CHECKING)
    }

    @Test
    fun `matched power menu without overlay falls back to back not home`() {
        assertThat(
            ProtectedPowerMenuController.powerMatchOverlayDecision(
                powerMatched = true,
                overlayShown = false
            )
        ).isEqualTo(ProtectedPowerMenuController.PowerMatchOverlayDecision.REQUEST_BACK_FALLBACK)
        assertThat(
            ProtectedPowerMenuController.powerMatchOverlayDecision(
                powerMatched = true,
                overlayShown = true
            )
        ).isEqualTo(ProtectedPowerMenuController.PowerMatchOverlayDecision.SHIELD_AND_CONSUME)
    }

    @Test
    fun `screen off keeps the shield and pauses rechecks until screen on`() {
        assertThat(ProtectedPowerMenuController.shouldKeepPowerOverlayOnScreenOff(true)).isTrue()
        assertThat(ProtectedPowerMenuController.shouldKeepPowerOverlayOnScreenOff(false)).isFalse()
        assertThat(ProtectedPowerMenuController.shouldScheduleRecheck(false, screenOff = true)).isFalse()
        assertThat(ProtectedPowerMenuController.shouldRecheckPowerOverlayOnScreenOn(true)).isTrue()
        assertThat(ProtectedPowerMenuController.shouldRecheckPowerOverlayOnScreenOn(false)).isFalse()
    }

    @Test
    fun `system ui event storm cannot postpone an already scheduled recheck`() {
        assertThat(ProtectedPowerMenuController.shouldScheduleRecheck(false)).isTrue()
        repeat(1_000) {
            assertThat(ProtectedPowerMenuController.shouldScheduleRecheck(true)).isFalse()
        }
    }

    @Test
    fun `blank package inspects only the exact candidate window`() {
        assertThat(
            ProtectedPowerMenuController.shouldInspectExactPowerWindow("", relevantEvent = true)
        ).isTrue()
        assertThat(
            ProtectedPowerMenuController.shouldInspectExactPowerWindow(
                "com.example.app",
                relevantEvent = true
            )
        ).isFalse()
    }

    @Test
    fun `external window does not get consumed by the power shield`() {
        assertThat(ProtectedPowerMenuController.shouldConsumeExternalWindowEvent()).isFalse()
    }

    @Test
    fun `feedback interrupt rechecks only while overlay is visible`() {
        assertThat(ProtectedPowerMenuController.shouldRecheckAfterFeedbackInterrupt(true)).isTrue()
        assertThat(ProtectedPowerMenuController.shouldRecheckAfterFeedbackInterrupt(false)).isFalse()
    }
}
'''
write(overlay_test_path, overlay_test)


# -----------------------------------------------------------------------------
# 3) Focus dial math: nearest endpoint in the 90-degree dead zone + exact display.
# -----------------------------------------------------------------------------
dial_math_path = 'app/src/main/java/com/focusguard/focusmode/FocusDurationDialMath.kt'
dial_math = '''package com.focusguard.focusmode

import kotlin.math.roundToInt

/** Pure geometry/formatting for the 270-degree Focus Mode duration dial. */
internal object FocusDurationDialMath {
    const val MINUTES_MIN = 1
    const val MINUTES_MAX = 480
    private const val START_ANGLE = 135f
    private const val END_ANGLE = 45f
    private const val GAP_MIDPOINT = 90f
    private const val SWEEP = 270f

    fun minutesForAngle(rawDegrees: Float): Int {
        val degrees = ((rawDegrees % 360f) + 360f) % 360f
        val relative = when {
            degrees >= START_ANGLE -> degrees - START_ANGLE
            degrees <= END_ANGLE -> degrees + (360f - START_ANGLE)
            // Pointer is in the inactive 90-degree gap. Snap to the nearest
            // endpoint instead of coercing the entire gap to eight hours.
            degrees <= GAP_MIDPOINT -> SWEEP
            else -> 0f
        }
        return (
            MINUTES_MIN + (relative / SWEEP) * (MINUTES_MAX - MINUTES_MIN)
        ).roundToInt().coerceIn(MINUTES_MIN, MINUTES_MAX)
    }

    fun displayValue(minutes: Int): String {
        val safe = minutes.coerceIn(MINUTES_MIN, MINUTES_MAX)
        if (safe < 60) return safe.toString()
        return "%d:%02d".format(safe / 60, safe % 60)
    }
}
'''
write(dial_math_path, dial_math)

dial_math_test_path = 'app/src/test/java/com/focusguard/focusmode/FocusDurationDialMathTest.kt'
dial_math_test = '''package com.focusguard.focusmode

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class FocusDurationDialMathTest {
    @Test
    fun `dial endpoints and sweep map to the expected range`() {
        assertThat(FocusDurationDialMath.minutesForAngle(135f)).isEqualTo(1)
        assertThat(FocusDurationDialMath.minutesForAngle(45f)).isEqualTo(480)
        assertThat(FocusDurationDialMath.minutesForAngle(270f)).isIn(240..242)
    }

    @Test
    fun `dead zone snaps to nearest endpoint instead of always eight hours`() {
        assertThat(FocusDurationDialMath.minutesForAngle(70f)).isEqualTo(480)
        assertThat(FocusDurationDialMath.minutesForAngle(120f)).isEqualTo(1)
    }

    @Test
    fun `hour display preserves minute precision`() {
        assertThat(FocusDurationDialMath.displayValue(40)).isEqualTo("40")
        assertThat(FocusDurationDialMath.displayValue(60)).isEqualTo("1:00")
        assertThat(FocusDurationDialMath.displayValue(65)).isEqualTo("1:05")
        assertThat(FocusDurationDialMath.displayValue(480)).isEqualTo("8:00")
    }
}
'''
write(dial_math_test_path, dial_math_test)


# -----------------------------------------------------------------------------
# 4) Focus UI polish/accessibility/full-screen explanation/dashed add tile.
# -----------------------------------------------------------------------------
focus_path = 'app/src/main/java/com/focusguard/ui/compose/screens/FocusModeScreen.kt'
focus = read(focus_path)
imports = {
    'import androidx.compose.material.icons.filled.Check\n': 'import androidx.compose.material.icons.filled.Check\n',
    'import androidx.compose.ui.draw.drawBehind\n': 'import androidx.compose.ui.draw.drawBehind\n',
    'import androidx.compose.ui.geometry.CornerRadius\n': 'import androidx.compose.ui.geometry.CornerRadius\n',
    'import androidx.compose.ui.graphics.PathEffect\n': 'import androidx.compose.ui.graphics.PathEffect\n',
    'import androidx.compose.ui.semantics.ProgressBarRangeInfo\n': 'import androidx.compose.ui.semantics.ProgressBarRangeInfo\n',
    'import androidx.compose.ui.semantics.contentDescription\n': 'import androidx.compose.ui.semantics.contentDescription\n',
    'import androidx.compose.ui.semantics.progressBarRangeInfo\n': 'import androidx.compose.ui.semantics.progressBarRangeInfo\n',
    'import androidx.compose.ui.semantics.semantics\n': 'import androidx.compose.ui.semantics.semantics\n',
    'import androidx.compose.ui.semantics.setProgress\n': 'import androidx.compose.ui.semantics.setProgress\n',
    'import androidx.compose.ui.semantics.stateDescription\n': 'import androidx.compose.ui.semantics.stateDescription\n',
    'import androidx.compose.ui.window.Dialog\n': 'import androidx.compose.ui.window.Dialog\n',
    'import androidx.compose.ui.window.DialogProperties\n': 'import androidx.compose.ui.window.DialogProperties\n',
    'import com.focusguard.focusmode.FocusDurationDialMath\n': 'import com.focusguard.focusmode.FocusDurationDialMath\n',
}
if 'import androidx.compose.material.icons.filled.Check\n' not in focus:
    focus = focus.replace('import androidx.compose.material.icons.filled.Apps\n', 'import androidx.compose.material.icons.filled.Apps\nimport androidx.compose.material.icons.filled.Check\n', 1)
if 'import androidx.compose.ui.draw.drawBehind\n' not in focus:
    focus = focus.replace('import androidx.compose.ui.draw.clip\n', 'import androidx.compose.ui.draw.clip\nimport androidx.compose.ui.draw.drawBehind\n', 1)
if 'import androidx.compose.ui.geometry.CornerRadius\n' not in focus:
    focus = focus.replace('import androidx.compose.ui.geometry.Offset\n', 'import androidx.compose.ui.geometry.CornerRadius\nimport androidx.compose.ui.geometry.Offset\n', 1)
if 'import androidx.compose.ui.graphics.PathEffect\n' not in focus:
    focus = focus.replace('import androidx.compose.ui.graphics.ImageBitmap\n', 'import androidx.compose.ui.graphics.ImageBitmap\nimport androidx.compose.ui.graphics.PathEffect\n', 1)
if 'import androidx.compose.ui.semantics.ProgressBarRangeInfo\n' not in focus:
    focus = focus.replace(
        'import androidx.compose.ui.semantics.Role\n',
        'import androidx.compose.ui.semantics.ProgressBarRangeInfo\nimport androidx.compose.ui.semantics.Role\nimport androidx.compose.ui.semantics.contentDescription\nimport androidx.compose.ui.semantics.progressBarRangeInfo\nimport androidx.compose.ui.semantics.semantics\nimport androidx.compose.ui.semantics.setProgress\nimport androidx.compose.ui.semantics.stateDescription\n',
        1
    )
if 'import androidx.compose.ui.window.Dialog\n' not in focus:
    focus = focus.replace('import androidx.compose.ui.unit.sp\n', 'import androidx.compose.ui.unit.sp\nimport androidx.compose.ui.window.Dialog\nimport androidx.compose.ui.window.DialogProperties\n', 1)
if 'import com.focusguard.focusmode.FocusDurationDialMath\n' not in focus:
    focus = focus.replace('import com.focusguard.focusmode.FocusModeAppCatalog\n', 'import com.focusguard.focusmode.FocusDurationDialMath\nimport com.focusguard.focusmode.FocusModeAppCatalog\n', 1)
focus = focus.replace('import kotlin.math.PI\n', '')

old_how = '''        if (showHowItWorks) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.78f))
                    .clickable { showHowItWorks = false }
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = surface),
                    border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.38f)),
                    shape = RoundedCornerShape(18.dp)
                ) {
                    Column(
                        modifier = Modifier.fillMaxWidth().padding(18.dp),
                        verticalArrangement = Arrangement.spacedBy(9.dp)
                    ) {
                        Text(
                            text = howTitle,
                            color = TextPrimary,
                            fontSize = 18.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = stringResource(R.string.focus_mode_static_purpose_body),
                            color = TextSecondary,
                            fontSize = 13.sp,
                            lineHeight = 19.sp
                        )
                        Text(
                            text = stringResource(R.string.focus_mode_tap_anywhere_to_close),
                            color = tertiaryText,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
'''
new_how = '''        if (showHowItWorks) {
            Dialog(
                onDismissRequest = { showHowItWorks = false },
                properties = DialogProperties(usePlatformDefaultWidth = false)
            ) {
                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(Color.Black.copy(alpha = 0.82f))
                        .clickable { showHowItWorks = false }
                        .padding(24.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Card(
                        colors = CardDefaults.cardColors(containerColor = surface),
                        border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.38f)),
                        shape = RoundedCornerShape(18.dp)
                    ) {
                        Column(
                            modifier = Modifier.fillMaxWidth().padding(18.dp),
                            verticalArrangement = Arrangement.spacedBy(9.dp)
                        ) {
                            Text(
                                text = howTitle,
                                color = TextPrimary,
                                fontSize = 18.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = stringResource(R.string.focus_mode_static_purpose_body),
                                color = TextSecondary,
                                fontSize = 13.sp,
                                lineHeight = 19.sp
                            )
                            Text(
                                text = stringResource(R.string.focus_mode_tap_anywhere_to_close),
                                color = tertiaryText,
                                fontSize = 11.sp
                            )
                        }
                    }
                }
            }
        }
'''
focus = replace_once(focus, old_how, new_how, 'fullscreen how-it-works')

old_display = '''    val displayNumber = when {
        minutes < 60 -> minutes.toString()
        minutes % 60 == 0 -> (minutes / 60).toString()
        else -> String.format(Locale.getDefault(), "%.1f", minutes / 60f)
    }
    val displayUnit = if (minutes < 60) minutesUnit.uppercase(Locale.getDefault())
    else hoursUnit.uppercase(Locale.getDefault())
'''
new_display = '''    val displayNumber = FocusDurationDialMath.displayValue(minutes)
    val displayUnit = if (minutes < 60) {
        minutesUnit.uppercase(Locale.getDefault())
    } else {
        "${hoursUnit.uppercase(Locale.getDefault())}:${minutesUnit.uppercase(Locale.getDefault())}"
    }
    val durationA11yLabel = stringResource(R.string.focus_mode_static_duration_section)
    val durationA11yValue = if (minutes < 60) {
        "$minutes $minutesUnit"
    } else {
        "${minutes / 60} $hoursUnit ${minutes % 60} $minutesUnit"
    }
'''
focus = replace_once(focus, old_display, new_display, 'dial exact display')

old_canvas_modifier = '''                modifier = Modifier
                    .fillMaxSize()
                    .pointerInput(Unit) {
                        fun update(position: Offset) {
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            var degrees = Math.toDegrees(
                                atan2(
                                    (position.y - cy).toDouble(),
                                    (position.x - cx).toDouble()
                                )
                            ).toFloat()
                            if (degrees < 0f) degrees += 360f
                            var relative = degrees - 135f
                            if (relative < 0f) relative += 360f
                            relative = relative.coerceIn(0f, 270f)
                            val next = (
                                1f + (relative / 270f) * (FOCUS_DURATION_MAX_MINUTES - 1f)
                            ).roundToInt().coerceIn(1, FOCUS_DURATION_MAX_MINUTES)
                            onMinutesChange(next)
                        }
'''
new_canvas_modifier = '''                modifier = Modifier
                    .fillMaxSize()
                    .semantics {
                        contentDescription = durationA11yLabel
                        stateDescription = durationA11yValue
                        progressBarRangeInfo = ProgressBarRangeInfo(
                            current = minutes.toFloat(),
                            range = 1f..FOCUS_DURATION_MAX_MINUTES.toFloat(),
                            steps = FOCUS_DURATION_MAX_MINUTES - 2
                        )
                        setProgress { target ->
                            onMinutesChange(
                                target.roundToInt().coerceIn(1, FOCUS_DURATION_MAX_MINUTES)
                            )
                            true
                        }
                    }
                    .pointerInput(minutes) {
                        fun update(position: Offset) {
                            val cx = size.width / 2f
                            val cy = size.height / 2f
                            val degrees = Math.toDegrees(
                                atan2(
                                    (position.y - cy).toDouble(),
                                    (position.x - cx).toDouble()
                                )
                            ).toFloat()
                            onMinutesChange(FocusDurationDialMath.minutesForAngle(degrees))
                        }
'''
focus = replace_once(focus, old_canvas_modifier, new_canvas_modifier, 'dial pointer and semantics')

old_tile_border = '''                    .clip(RoundedCornerShape(15.dp))
                    .background(surface2)
                    .border(1.dp, if (dashedStyle) stroke else Color.Transparent, RoundedCornerShape(15.dp)),
'''
new_tile_border = '''                    .clip(RoundedCornerShape(15.dp))
                    .background(surface2)
                    .then(
                        if (dashedStyle) {
                            Modifier.drawBehind {
                                drawRoundRect(
                                    color = stroke,
                                    cornerRadius = CornerRadius(15.dp.toPx()),
                                    style = Stroke(
                                        width = 1.5.dp.toPx(),
                                        pathEffect = PathEffect.dashPathEffect(
                                            floatArrayOf(6.dp.toPx(), 5.dp.toPx())
                                        )
                                    )
                                )
                            }
                        } else {
                            Modifier
                        }
                    ),
'''
focus = replace_once(focus, old_tile_border, new_tile_border, 'dashed add tile')
focus = focus.replace(
    'if (selected) Icons.Default.Add else Icons.Default.Lock',
    'if (selected) Icons.Default.Check else Icons.Default.Lock'
)
write(focus_path, focus)

print('All critic corrections applied.')
