package com.focusguard.service

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.focusguard.R
import com.focusguard.security.PowerMenuProtectionPolicy
import com.focusguard.security.PowerMenuProtectionPolicy.Action
import com.focusguard.utils.FocusGuardLogger

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
    private val mainHandler = Handler(Looper.getMainLooper())
    private val windowManager =
        service.getSystemService(Context.WINDOW_SERVICE) as WindowManager

    private var overlay: View? = null
    private var statusText: TextView? = null

    fun handleAccessibilityEvent(
        event: AccessibilityEvent,
        protectionActive: Boolean
    ): Boolean {
        if (!protectionActive) {
            dismiss()
            return false
        }

        val packageName = event.packageName?.toString().orEmpty()
        if (overlay != null && packageName == service.packageName) return true

        val relevantEvent = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED

        if (!relevantEvent) {
            return overlay != null && PowerMenuProtectionPolicy.isSystemUiPackage(packageName)
        }

        if (PowerMenuProtectionPolicy.isSystemUiPackage(packageName)) {
            val root = findSystemUiRoot()
            val values = buildList<CharSequence?> {
                addAll(event.text)
                add(event.contentDescription)
                if (root != null) collectText(root, this, 0)
            }
            val isPowerMenu = PowerMenuProtectionPolicy.isPowerMenu(
                packageName = packageName,
                className = event.className?.toString().orEmpty(),
                values = values
            )
            recycleSafely(root)

            if (isPowerMenu) {
                show()
                return true
            }

            if (overlay != null && event.eventType != AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED) {
                scheduleRecheck()
                return true
            }
            return overlay != null
        }

        if (overlay != null &&
            packageName.isNotBlank() &&
            packageName != service.packageName &&
            event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED
        ) {
            dismiss()
        }
        return false
    }

    fun onProtectionStateChanged(active: Boolean) {
        if (!active) dismiss()
    }

    fun dismiss() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(::dismiss)
            return
        }
        mainHandler.removeCallbacks(recheckRunnable)
        val current = overlay ?: return
        overlay = null
        statusText = null
        runCatching { windowManager.removeViewImmediate(current) }
            .onFailure { error ->
                FocusGuardLogger.logError(
                    "PowerMenu",
                    "Falha ao remover menu de energia protegido",
                    error
                )
            }
    }

    private fun show() {
        if (Looper.myLooper() != Looper.getMainLooper()) {
            mainHandler.post(::show)
            return
        }
        if (overlay != null) return

        val density = service.resources.displayMetrics.density
        val horizontal = (28 * density).toInt()
        val vertical = (16 * density).toInt()
        val gap = (10 * density).toInt()

        val container = LinearLayout(service).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER
            setPadding(horizontal, horizontal, horizontal, horizontal)
            setBackgroundColor(Color.rgb(16, 17, 23))
            isClickable = true
            isFocusable = true
            contentDescription = service.getString(R.string.protected_power_menu_title)
        }

        container.addView(TextView(service).apply {
            text = service.getString(R.string.protected_power_menu_title)
            setTextColor(Color.WHITE)
            textSize = 24f
            gravity = Gravity.CENTER
        })
        container.addView(TextView(service).apply {
            text = service.getString(R.string.protected_power_menu_subtitle)
            setTextColor(Color.LTGRAY)
            textSize = 14f
            gravity = Gravity.CENTER
            setPadding(0, gap, 0, vertical)
        })

        addActionButton(container, R.string.protected_power_menu_power_off, Action.POWER_OFF)
        addActionButton(container, R.string.protected_power_menu_restart, Action.RESTART)
        addActionButton(container, R.string.protected_power_menu_emergency, Action.EMERGENCY)
        addActionButton(container, R.string.protected_power_menu_medical_info, Action.MEDICAL_INFO)

        container.addView(Button(service).apply {
            text = service.getString(R.string.protected_power_menu_cancel)
            isAllCaps = false
            setOnClickListener {
                service.performGlobalAction(AccessibilityService.GLOBAL_ACTION_BACK)
                mainHandler.postDelayed(::dismiss, CANCEL_DISMISS_DELAY_MILLIS)
            }
            setOnLongClickListener { true }
        }, buttonParams(gap))

        container.addView(TextView(service).apply {
            setTextColor(Color.LTGRAY)
            textSize = 12f
            gravity = Gravity.CENTER
            visibility = View.GONE
            statusText = this
        }, LinearLayout.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = gap })

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.OPAQUE
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            title = "FocusGuardProtectedPowerMenu"
        }

        runCatching {
            windowManager.addView(container, params)
            overlay = container
        }.onFailure { error ->
            FocusGuardLogger.logError(
                "PowerMenu",
                "Falha ao exibir menu de energia protegido",
                error
            )
        }
    }

    private fun addActionButton(
        parent: LinearLayout,
        labelRes: Int,
        action: Action
    ) {
        parent.addView(Button(service).apply {
            text = service.getString(labelRes)
            isAllCaps = false
            setOnClickListener { performNativeSinglePress(action) }
            setOnLongClickListener { true }
        }, buttonParams((10 * service.resources.displayMetrics.density).toInt()))
    }

    private fun buttonParams(topMargin: Int) = LinearLayout.LayoutParams(
        WindowManager.LayoutParams.MATCH_PARENT,
        WindowManager.LayoutParams.WRAP_CONTENT
    ).apply { this.topMargin = topMargin }

    private fun performNativeSinglePress(action: Action) {
        val root = findSystemUiRoot()
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

    private fun findSystemUiRoot(): AccessibilityNodeInfo? {
        service.windows.forEach { window ->
            val root = runCatching { window.root }.getOrNull() ?: return@forEach
            val packageName = runCatching { root.packageName?.toString().orEmpty() }
                .getOrDefault("")
            if (PowerMenuProtectionPolicy.isSystemUiPackage(packageName)) return root
            recycleSafely(root)
        }
        return null
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
        mainHandler.removeCallbacks(recheckRunnable)
        mainHandler.postDelayed(recheckRunnable, RECHECK_DELAY_MILLIS)
    }

    private val recheckRunnable = Runnable {
        val root = findSystemUiRoot()
        if (root == null) {
            dismiss()
            return@Runnable
        }
        val values = mutableListOf<CharSequence?>()
        collectText(root, values, 0)
        val stillPowerMenu = PowerMenuProtectionPolicy.isPowerMenu(
            packageName = root.packageName?.toString().orEmpty(),
            className = root.className?.toString().orEmpty(),
            values = values
        )
        recycleSafely(root)
        if (!stillPowerMenu) dismiss()
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

    private companion object {
        const val MAX_PARENT_DEPTH = 5
        const val MAX_TREE_DEPTH = 12
        const val MAX_CHILDREN_PER_NODE = 30
        const val MAX_TEXT_VALUES = 300
        const val RECHECK_DELAY_MILLIS = 350L
        const val CANCEL_DISMISS_DELAY_MILLIS = 120L
    }
}
