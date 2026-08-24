package com.focusguard.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.StateListDrawable
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import com.focusguard.R
import com.focusguard.security.PowerMenuProtectionPolicy
import com.focusguard.security.PowerMenuProtectionPolicy.Action
import com.focusguard.security.PowerMenuProtectionPolicy.DirectDecision
import com.focusguard.utils.FocusGuardLogger

private object HardBlockPowerMenuColors {
    val backgroundTop = Color.rgb(13, 13, 13)
    val backgroundBottom = Color.rgb(8, 9, 11)
    val surface = Color.rgb(22, 22, 22)
    val card = Color.rgb(28, 28, 30)
    val cardPressed = Color.rgb(37, 37, 40)
    val accent = Color.rgb(0, 188, 212)
    val danger = Color.rgb(229, 57, 53)
    val textPrimary = Color.rgb(250, 250, 250)
    val textSecondary = Color.rgb(176, 176, 176)
    val textHint = Color.rgb(107, 107, 107)
    val border = Color.rgb(48, 48, 53)
}

/**
 * Visible accessibility overlay that shields the native power menu during an
 * active HardBlock session.
 *
 * System UI stays underneath so OEM shutdown/restart/emergency behavior is kept.
 * The user never touches those native controls: this overlay consumes touch and
 * forwards only ACTION_CLICK, never ACTION_LONG_CLICK.
 */
class ProtectedPowerMenuController(
    private val service: AccessibilityService
) {
    internal enum class PowerMenuPresence { PRESENT, ABSENT_CONFIRMED, UNKNOWN }
    internal enum class CloseStage { NONE, BACK_REQUESTED, HOME_REQUESTED }
    internal enum class RecheckDecision { HIDE, KEEP_CHECKING, REQUEST_BACK, REQUEST_HOME }
    internal enum class PowerMatchOverlayDecision {
        PASS,
        SHIELD_AND_CONSUME,
        REQUEST_HOME_FALLBACK
    }

    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager =
        service.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var overlay: View? = null
    private var overlayParams: WindowManager.LayoutParams? = null
    private var overlayAttached = false
    private var overlayVisible = false
    private var overlayShownAtElapsed = 0L
    private var directSignalActive = false
    private var directMatchedWindowId = -1
    private var directSignalAtElapsed = 0L
    private var reliableWindowObserved = false
    private var closeStage = CloseStage.NONE
    private var closeStageAtElapsed = 0L
    private var homeFallbackAttempted = false
    private var recheckScheduled = false
    private var protectionActive = false
    private var statusText: TextView? = null

    fun handleAccessibilityEvent(
        event: AccessibilityEvent,
        protectionActive: Boolean
    ): Boolean {
        if (this.protectionActive != protectionActive) {
            onProtectionStateChanged(protectionActive)
        }
        if (!protectionActive) {
            return false
        }

        val packageName = event.packageName?.toString().orEmpty()
        if (overlayVisible && packageName == service.packageName) return true

        val relevantEvent = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED

        if (!relevantEvent) {
            return overlayVisible && PowerMenuProtectionPolicy.isSystemUiPackage(packageName)
        }

        // Some OEMs omit the package on the first global-actions event. Check
        // only that event's exact window before the broader package-specific
        // path; rootMatchesPowerMenu still requires a real System UI signature.
        if (shouldInspectExactPowerWindow(packageName, relevantEvent)) {
            val powerMenuRoot = findPowerMenuRoot(event, scanAllWindows = false)
            if (powerMenuRoot != null) {
                reliableWindowObserved = true
                recycleSafely(powerMenuRoot)
                return protectMatchedPowerMenu()
            }
            if (overlayVisible) scheduleRecheck()
            return false
        }

        if (PowerMenuProtectionPolicy.isSystemUiPackage(packageName)) {
            when (PowerMenuProtectionPolicy.classifyDirect(
                packageName = packageName,
                className = event.className?.toString().orEmpty(),
                values = buildList {
                    addAll(event.text.orEmpty())
                    add(event.contentDescription)
                }
            )) {
                DirectDecision.MATCH -> {
                    if (!overlayVisible) reliableWindowObserved = false
                    directSignalActive = true
                    directMatchedWindowId = event.windowId
                    directSignalAtElapsed = SystemClock.elapsedRealtime()
                    if (event.windowId >= 0) reliableWindowObserved = true
                    return protectMatchedPowerMenu()
                }
                DirectDecision.NOT_MATCH -> {
                    if (overlayVisible &&
                        directSignalActive &&
                        directMatchedWindowId >= 0 &&
                        event.windowId == directMatchedWindowId &&
                        event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
                    ) {
                        dismiss()
                        return false
                    }
                    return overlayVisible
                }
                DirectDecision.UNKNOWN -> Unit
            }

            // Prefer the exact window that emitted this event. If an OEM reports a
            // different/blank window id, scan all System UI windows and require a
            // real power-menu signature before selecting one. This avoids confusing
            // notification shade/status-bar windows with global actions.
            val powerMenuRoot = findPowerMenuRoot(event)
            if (powerMenuRoot != null) {
                reliableWindowObserved = true
                recycleSafely(powerMenuRoot)
                return protectMatchedPowerMenu()
            }

            if (overlayVisible) {
                scheduleRecheck()
                return true
            }
            return false
        }

        if (overlayVisible &&
            packageName.isNotBlank() &&
            packageName != service.packageName &&
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            // Another app/window can report a delayed transition while the
            // native power menu is still present (including split screen).
            // Never uncover System UI on that unrelated signal; the scheduled
            // recheck hides only after the tracked window/root is absent.
            scheduleRecheck()
            // Do not consume it: the accessibility service still needs the same
            // event to block that app/Settings target underneath this shield.
            return shouldConsumeExternalWindowEvent()
        }
        return false
    }

    fun onProtectionStateChanged(active: Boolean) {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post { onProtectionStateChanged(active) }
            return
        }
        protectionActive = active
        if (active) {
            prepareOverlay()
            attachOverlayHidden()
        } else {
            release()
        }
    }

    fun dismiss() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(::dismiss)
            return
        }
        mainHandler.removeCallbacks(recheckRunnable)
        recheckScheduled = false
        if (overlayAttached && overlayVisible) {
            val current = overlay
            val params = overlayParams
            if (current != null && params != null) {
                params.alpha = 0f
                params.flags = hiddenFlags(params.flags)
                runCatching { windowManager.updateViewLayout(current, params) }
                    .onFailure { error ->
                        FocusGuardLogger.logError(
                            "PowerMenu",
                            "Falha ao ocultar menu de energia protegido",
                            error
                        )
                        release()
                    }
            }
        }
        overlayVisible = false
        overlayShownAtElapsed = 0L
        resetDetectionState()
    }

    /** Accessibility feedback interruption says nothing about native window state. */
    fun onFeedbackInterrupted() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(::onFeedbackInterrupted)
            return
        }
        if (shouldRecheckAfterFeedbackInterrupt(overlayVisible)) scheduleRecheck()
    }

    /** Screen-off is not proof that an OEM global-actions window disappeared. */
    fun onScreenOff() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(::onScreenOff)
            return
        }
        if (shouldRequestCloseOnScreenOff(overlayVisible)) {
            requestNativeHomeClose()
        }
    }

    fun destroy() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(::destroy)
            return
        }
        protectionActive = false
        release()
    }

    private fun show(): Boolean {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            return false
        }
        prepareOverlay()
        attachOverlayHidden()
        if (!overlayAttached) return false
        if (overlayVisible) return true
        val current = overlay ?: return false
        val params = overlayParams ?: return false
        statusText?.visibility = View.GONE
        params.alpha = 1f
        params.flags = visibleFlags(params.flags)
        return runCatching {
            windowManager.updateViewLayout(current, params)
            overlayVisible = true
            overlayShownAtElapsed = SystemClock.elapsedRealtime()
            closeStage = CloseStage.NONE
            closeStageAtElapsed = 0L
            homeFallbackAttempted = false
            true
        }.onFailure { error ->
            FocusGuardLogger.logError(
                "PowerMenu",
                "Falha ao exibir menu de energia protegido",
                error
            )
            release()
        }.getOrDefault(false)
    }

    private fun protectMatchedPowerMenu(): Boolean = when (
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

    private fun prepareOverlay() {
        if (overlay != null) return

        val root = FrameLayout(service).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.TOP_BOTTOM,
                intArrayOf(
                    HardBlockPowerMenuColors.backgroundTop,
                    HardBlockPowerMenuColors.backgroundBottom
                )
            )
            isClickable = true
            isFocusable = true
            contentDescription = service.getString(R.string.protected_power_menu_title)
        }

        val sheet = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.START
            setPadding(dp(20), dp(12), dp(20), dp(18))
            background = topRoundedGradient(
                intArrayOf(
                    HardBlockPowerMenuColors.surface,
                    HardBlockPowerMenuColors.backgroundTop
                ),
                32
            )
            elevation = dp(18).toFloat()
        }

        sheet.addView(View(service).apply {
            background = roundedBackground(
                Color.argb(34, 255, 255, 255),
                radiusDp = 99
            )
        }, LinearLayout.LayoutParams(dp(34), dp(4)).apply {
            gravity = Gravity.CENTER_HORIZONTAL
            bottomMargin = dp(20)
        })

        sheet.addView(TextView(service).apply {
            text = service.getString(R.string.protected_power_menu_badge)
            setTextColor(HardBlockPowerMenuColors.accent)
            textSize = 10f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.12f
            gravity = Gravity.CENTER
            setPadding(dp(10), dp(6), dp(10), dp(6))
            background = roundedBackground(
                Color.argb(18, 0, 188, 212),
                radiusDp = 99,
                strokeColor = Color.argb(76, 0, 188, 212)
            )
        }, LinearLayout.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply { bottomMargin = dp(16) })

        sheet.addView(TextView(service).apply {
            text = service.getString(R.string.protected_power_menu_title)
            setTextColor(HardBlockPowerMenuColors.textPrimary)
            textSize = 29f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            letterSpacing = -0.02f
        }, LinearLayout.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        ))

        sheet.addView(TextView(service).apply {
            text = service.getString(R.string.protected_power_menu_subtitle)
            setTextColor(HardBlockPowerMenuColors.textSecondary)
            textSize = 14f
            setLineSpacing(0f, 1.12f)
        }, LinearLayout.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(7)
            bottomMargin = dp(16)
        })

        addActionCard(sheet, R.string.protected_power_menu_power_off, Action.POWER_OFF)
        addActionCard(sheet, R.string.protected_power_menu_restart, Action.RESTART)

        addSectionDivider(sheet)

        addActionCard(
            sheet,
            R.string.protected_power_menu_emergency,
            Action.EMERGENCY,
            emergency = true
        )
        addActionCard(
            sheet,
            R.string.protected_power_menu_medical_info,
            Action.MEDICAL_INFO,
            emergency = true
        )

        sheet.addView(TextView(service).apply {
            setTextColor(HardBlockPowerMenuColors.textSecondary)
            textSize = 12f
            gravity = Gravity.CENTER
            visibility = View.GONE
            statusText = this
        }, LinearLayout.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(12) })

        sheet.addView(TextView(service).apply {
            text = service.getString(R.string.protected_power_menu_cancel)
            setTextColor(HardBlockPowerMenuColors.textSecondary)
            textSize = 15f
            gravity = Gravity.CENTER
            isClickable = true
            isFocusable = true
            background = cancelBackground()
            setOnClickListener {
                // Keep shielding System UI until a later recheck proves the
                // native power window is gone.
                requestNativeBackClose()
            }
            setOnLongClickListener { true }
        }, LinearLayout.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            dp(52)
        ).apply { topMargin = dp(10) })

        root.addView(sheet, FrameLayout.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply { gravity = Gravity.BOTTOM })

        overlay = root
        overlayParams = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
                WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "FocusGuardProtectedPowerMenu"
            alpha = 0f
        }
    }

    private fun attachOverlayHidden() {
        if (overlayAttached) return
        val current = overlay ?: return
        val params = overlayParams ?: return
        params.alpha = 0f
        params.flags = hiddenFlags(params.flags)
        runCatching {
            windowManager.addView(current, params)
            overlayAttached = true
            overlayVisible = false
            overlayShownAtElapsed = 0L
        }.onFailure { error ->
            FocusGuardLogger.logError(
                "PowerMenu",
                "Falha ao pré-anexar menu de energia protegido",
                error
            )
        }
    }

    private fun release() {
        mainHandler.removeCallbacks(recheckRunnable)
        recheckScheduled = false
        val current = overlay
        if (current != null && overlayAttached) {
            runCatching { windowManager.removeViewImmediate(current) }
                .onFailure { error ->
                    FocusGuardLogger.logError(
                        "PowerMenu",
                        "Falha ao liberar menu de energia protegido",
                        error
                    )
                }
        }
        overlayAttached = false
        overlayVisible = false
        overlayShownAtElapsed = 0L
        directSignalActive = false
        directMatchedWindowId = -1
        directSignalAtElapsed = 0L
        reliableWindowObserved = false
        closeStage = CloseStage.NONE
        closeStageAtElapsed = 0L
        homeFallbackAttempted = false
    }

    private fun addActionCard(
        parent: LinearLayout,
        labelRes: Int,
        action: Action,
        emergency: Boolean = false
    ) {
        val accent = if (emergency) {
            HardBlockPowerMenuColors.danger
        } else {
            HardBlockPowerMenuColors.accent
        }
        val label = service.getString(labelRes)

        val row = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), 0, dp(16), 0)
            isClickable = true
            isFocusable = true
            contentDescription = label
            background = actionCardBackground(emergency)
            setOnClickListener { performNativeSinglePress(action) }
            // The overlay may visually resemble a power menu, but HardBlock still
            // forwards only ACTION_CLICK. Never forward the native long-click that
            // some OEMs use to expose Safe Mode.
            setOnLongClickListener { true }
        }

        row.addView(TextView(service).apply {
            text = actionIcon(action)
            setTextColor(accent)
            textSize = if (action == Action.POWER_OFF) 22f else 21f
            gravity = Gravity.CENTER
            background = roundedBackground(
                if (emergency) {
                    Color.argb(20, 229, 57, 53)
                } else {
                    Color.argb(18, 0, 188, 212)
                },
                radiusDp = 11,
                strokeColor = if (emergency) {
                    Color.argb(58, 229, 57, 53)
                } else {
                    Color.argb(52, 0, 188, 212)
                }
            )
        }, LinearLayout.LayoutParams(dp(36), dp(36)).apply {
            marginEnd = dp(14)
        })

        row.addView(TextView(service).apply {
            text = label
            setTextColor(HardBlockPowerMenuColors.textPrimary)
            textSize = 16f
            setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
            gravity = Gravity.CENTER_VERTICAL
        }, LinearLayout.LayoutParams(0,
            WindowManager.LayoutParams.WRAP_CONTENT,
            1f
        ))

        parent.addView(row, LinearLayout.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            dp(62)
        ).apply { topMargin = dp(9) })
    }

    private fun addSectionDivider(parent: LinearLayout) {
        val divider = LinearLayout(service).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
        }

        divider.addView(TextView(service).apply {
            text = service.getString(R.string.protected_power_menu_emergency_section)
            setTextColor(Color.argb(190, 229, 57, 53))
            textSize = 9.5f
            typeface = Typeface.MONOSPACE
            letterSpacing = 0.14f
        }, LinearLayout.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        ))

        divider.addView(View(service).apply {
            background = GradientDrawable(
                GradientDrawable.Orientation.LEFT_RIGHT,
                intArrayOf(Color.argb(64, 229, 57, 53), Color.TRANSPARENT)
            )
        }, LinearLayout.LayoutParams(0, dp(1), 1f).apply {
            marginStart = dp(11)
        })

        parent.addView(divider, LinearLayout.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply {
            topMargin = dp(22)
            bottomMargin = dp(2)
        })
    }

    private fun actionIcon(action: Action): String = when (action) {
        Action.POWER_OFF -> "⏻"
        Action.RESTART -> "↻"
        Action.EMERGENCY -> "☎"
        Action.MEDICAL_INFO -> "✚"
    }

    private fun actionCardBackground(emergency: Boolean): StateListDrawable {
        val normalFill = if (emergency) {
            Color.argb(13, 229, 57, 53)
        } else {
            HardBlockPowerMenuColors.card
        }
        val pressedFill = if (emergency) {
            Color.argb(26, 229, 57, 53)
        } else {
            HardBlockPowerMenuColors.cardPressed
        }
        val stroke = if (emergency) {
            Color.argb(62, 229, 57, 53)
        } else {
            HardBlockPowerMenuColors.border
        }
        return StateListDrawable().apply {
            addState(
                intArrayOf(android.R.attr.state_pressed),
                roundedBackground(pressedFill, 17, stroke)
            )
            addState(intArrayOf(), roundedBackground(normalFill, 17, stroke))
        }
    }

    private fun cancelBackground(): StateListDrawable = StateListDrawable().apply {
        addState(
            intArrayOf(android.R.attr.state_pressed),
            roundedBackground(Color.argb(12, 255, 255, 255), 14)
        )
        addState(intArrayOf(), roundedBackground(Color.TRANSPARENT, 14))
    }

    private fun roundedBackground(
        color: Int,
        radiusDp: Int,
        strokeColor: Int? = null
    ): GradientDrawable = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        strokeColor?.let { setStroke(dp(1), it) }
    }

    private fun topRoundedGradient(colors: IntArray, radiusDp: Int): GradientDrawable {
        val radius = dp(radiusDp).toFloat()
        return GradientDrawable(GradientDrawable.Orientation.TOP_BOTTOM, colors).apply {
            cornerRadii = floatArrayOf(
                radius, radius,
                radius, radius,
                0f, 0f,
                0f, 0f
            )
            setStroke(dp(1), Color.argb(18, 255, 255, 255))
        }
    }

    private fun dp(value: Int): Int =
        (value * service.resources.displayMetrics.density + 0.5f).toInt()

    private fun performNativeSinglePress(action: Action) {
        val root = findPowerMenuRoot()
        if (root == null) {
            showStatus(R.string.protected_power_menu_action_unavailable)
            return
        }

        val node = findActionNode(root, action)
        val clicked = node?.let(::clickNodeOrParent) == true
        recycleSafely(node)
        recycleSafely(root)

        if (!clicked) {
            showStatus(R.string.protected_power_menu_action_unavailable)
            return
        }

        showStatus(R.string.protected_power_menu_action_sent)
        scheduleRecheck()
    }

    private fun findActionNode(root: AccessibilityNodeInfo, action: Action): AccessibilityNodeInfo? {
        PowerMenuProtectionPolicy.termsFor(action).forEach { term ->
            val nodes = runCatching { root.findAccessibilityNodeInfosByText(term) }
                .getOrDefault(emptyList())
            var selected: AccessibilityNodeInfo? = null
            nodes.forEach { node ->
                if (selected == null && PowerMenuProtectionPolicy.matchesAction(
                        action,
                        listOf(node.text, node.contentDescription, node.viewIdResourceName)
                    )
                ) {
                    selected = node
                } else {
                    recycleSafely(node)
                }
            }
            if (selected != null) return selected
        }
        return null
    }

    private fun clickNodeOrParent(start: AccessibilityNodeInfo): Boolean {
        var current: AccessibilityNodeInfo? = start
        var ownsCurrent = false
        repeat(MAX_PARENT_DEPTH) {
            val node = current ?: return false
            if (node.isClickable &&
                runCatching { node.performAction(AccessibilityNodeInfo.ACTION_CLICK) }
                    .getOrDefault(false)
            ) {
                if (ownsCurrent) recycleSafely(node)
                return true
            }
            val parent = runCatching { node.parent }.getOrNull()
            if (ownsCurrent) recycleSafely(node)
            current = parent
            ownsCurrent = true
        }
        if (ownsCurrent) recycleSafely(current)
        return false
    }

    /**
     * Returns only a System UI root that independently matches the power-menu
     * signature. When an event is available, its window is checked first.
     */
    private fun findPowerMenuRoot(
        event: AccessibilityEvent? = null,
        scanAllWindows: Boolean = true
    ): AccessibilityNodeInfo? {
        val windows = service.windows
        val eventWindowId = event?.windowId

        if (event != null && eventWindowId != null) {
            val eventWindow = windows.firstOrNull { it.id == eventWindowId }
            val root = runCatching { eventWindow?.root }.getOrNull()
            if (root != null) {
                if (rootMatchesPowerMenu(root, event)) return root
                recycleSafely(root)
            }
        }

        if (!scanAllWindows) return null

        windows.forEach { window ->
            if (eventWindowId != null && window.id == eventWindowId) return@forEach
            val root = runCatching { window.root }.getOrNull() ?: return@forEach
            if (rootMatchesPowerMenu(root, null)) return root
            recycleSafely(root)
        }
        return null
    }

    private fun rootMatchesPowerMenu(
        root: AccessibilityNodeInfo,
        event: AccessibilityEvent?
    ): Boolean {
        val packageName = runCatching { root.packageName?.toString().orEmpty() }
            .getOrDefault("")
        if (!PowerMenuProtectionPolicy.isSystemUiPackage(packageName)) return false

        val values = mutableListOf<CharSequence?>()
        event?.let {
            values.addAll(it.text)
            values.add(it.contentDescription)
        }
        collectText(root, values, 0)
        return PowerMenuProtectionPolicy.classifyDirect(
            packageName = packageName,
            className = event?.className?.toString()
                ?: root.className?.toString().orEmpty(),
            values = values
        ) == DirectDecision.MATCH
    }

    private fun collectText(
        node: AccessibilityNodeInfo,
        output: MutableList<CharSequence?>,
        depth: Int
    ) {
        if (depth > MAX_TREE_DEPTH || output.size >= MAX_TEXT_VALUES) return
        output += node.text
        output += node.contentDescription
        output += node.viewIdResourceName
        val count = node.childCount.coerceAtMost(MAX_CHILDREN_PER_NODE)
        for (index in 0 until count) {
            if (output.size >= MAX_TEXT_VALUES) break
            val child = runCatching { node.getChild(index) }.getOrNull() ?: continue
            try {
                collectText(child, output, depth + 1)
            } finally {
                recycleSafely(child)
            }
        }
    }

    private fun scheduleRecheck() {
        if (!shouldScheduleRecheck(recheckScheduled)) return
        recheckScheduled = true
        mainHandler.postDelayed(recheckRunnable, RECHECK_DELAY_MILLIS)
    }

    private val recheckRunnable = Runnable {
        recheckScheduled = false
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
            homeFallbackAttempted = homeFallbackAttempted,
            unconfirmedSignalGraceExpired = directSignalActive &&
                directMatchedWindowId < 0 &&
                !undefinedWindowGraceActive
        )) {
            RecheckDecision.HIDE -> dismiss()
            RecheckDecision.KEEP_CHECKING -> scheduleRecheck()
            RecheckDecision.REQUEST_BACK -> requestNativeBackClose()
            RecheckDecision.REQUEST_HOME -> requestNativeHomeClose()
        }
        recycleSafely(root)
    }

    private fun requestNativeBackClose() {
        if (!overlayVisible || closeStage != CloseStage.NONE) return
        closeStage = CloseStage.BACK_REQUESTED
        closeStageAtElapsed = SystemClock.elapsedRealtime()
        service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
        scheduleRecheck()
    }

    private fun requestNativeHomeClose() {
        if (!overlayVisible) return
        val retryingPersistentWindow = closeStage == CloseStage.HOME_REQUESTED
        closeStage = CloseStage.HOME_REQUESTED
        closeStageAtElapsed = SystemClock.elapsedRealtime()
        val globalHomeAccepted =
            service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_HOME)
        if (shouldLaunchHomeIntentFallback(
                globalHomeAccepted = globalHomeAccepted,
                retryingPersistentWindow = retryingPersistentWindow
            )
        ) {
            val fallbackResult = runCatching {
                service.startActivity(
                    Intent(Intent.ACTION_MAIN).apply {
                        addCategory(Intent.CATEGORY_HOME)
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
                    }
                )
            }.onFailure { error ->
                FocusGuardLogger.logError(
                    "PowerMenu",
                    "Falha no fallback HOME ao fechar menu nativo",
                    error
                )
            }
            homeFallbackAttempted = shouldMarkHomeFallbackAttempted(
                fallbackIntentSucceeded = fallbackResult.isSuccess
            )
        }
        scheduleRecheck()
    }

    private fun resetDetectionState() {
        directSignalActive = false
        directMatchedWindowId = -1
        directSignalAtElapsed = 0L
        reliableWindowObserved = false
        closeStage = CloseStage.NONE
        closeStageAtElapsed = 0L
        homeFallbackAttempted = false
    }

    private fun showStatus(resId: Int) {
        statusText?.apply {
            setText(resId)
            visibility = View.VISIBLE
        }
    }

    private fun recycleSafely(node: AccessibilityNodeInfo?) {
        if (node == null) return
        runCatching { node.recycle() }
    }

    companion object {
        internal fun powerMatchOverlayDecision(
            powerMatched: Boolean,
            overlayShown: Boolean
        ): PowerMatchOverlayDecision = when {
            !powerMatched -> PowerMatchOverlayDecision.PASS
            overlayShown -> PowerMatchOverlayDecision.SHIELD_AND_CONSUME
            else -> PowerMatchOverlayDecision.REQUEST_HOME_FALLBACK
        }

        internal fun recheckDecision(
            overlayVisible: Boolean,
            presence: PowerMenuPresence,
            visibleForMillis: Long,
            closeStage: CloseStage,
            closeStageForMillis: Long,
            homeFallbackAttempted: Boolean = false,
            unconfirmedSignalGraceExpired: Boolean
        ): RecheckDecision = when {
            !overlayVisible -> RecheckDecision.HIDE
            presence == PowerMenuPresence.ABSENT_CONFIRMED -> RecheckDecision.HIDE
            closeStage == CloseStage.HOME_REQUESTED &&
                closeStageForMillis >= HOME_CLOSE_HARD_CAP_MILLIS &&
                presence == PowerMenuPresence.PRESENT -> RecheckDecision.REQUEST_HOME
            closeStage == CloseStage.HOME_REQUESTED &&
                closeStageForMillis >= HOME_CLOSE_HARD_CAP_MILLIS &&
                !homeFallbackAttempted -> RecheckDecision.REQUEST_HOME
            closeStage == CloseStage.HOME_REQUESTED &&
                closeStageForMillis >= HOME_CLOSE_HARD_CAP_MILLIS -> RecheckDecision.HIDE
            closeStage == CloseStage.BACK_REQUESTED &&
                closeStageForMillis >= BACK_TO_HOME_MILLIS -> RecheckDecision.REQUEST_HOME
            closeStage != CloseStage.NONE -> RecheckDecision.KEEP_CHECKING
            unconfirmedSignalGraceExpired -> RecheckDecision.REQUEST_BACK
            visibleForMillis >= MAX_OVERLAY_VISIBLE_MILLIS -> RecheckDecision.REQUEST_BACK
            else -> RecheckDecision.KEEP_CHECKING
        }

        internal fun hiddenFlags(flags: Int): Int =
            flags or WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE

        internal fun visibleFlags(flags: Int): Int =
            flags and WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE.inv() and
                WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE.inv()

        internal fun shouldScheduleRecheck(alreadyScheduled: Boolean): Boolean =
            !alreadyScheduled

        internal fun shouldLaunchHomeIntentFallback(
            globalHomeAccepted: Boolean,
            retryingPersistentWindow: Boolean
        ): Boolean = !globalHomeAccepted || retryingPersistentWindow

        internal fun shouldMarkHomeFallbackAttempted(
            fallbackIntentSucceeded: Boolean
        ): Boolean = fallbackIntentSucceeded

        internal fun shouldConsumeExternalWindowEvent(): Boolean = false

        internal fun shouldRecheckAfterFeedbackInterrupt(
            overlayVisible: Boolean
        ): Boolean = overlayVisible

        internal fun shouldRequestCloseOnScreenOff(
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
        const val BACK_TO_HOME_MILLIS = 1_050L
        const val HOME_CLOSE_HARD_CAP_MILLIS = 1_050L
        const val MAX_OVERLAY_VISIBLE_MILLIS = 30_000L
    }
}
