package com.focusguard.security

import android.app.Activity
import android.app.Application
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import com.focusguard.service.BlockingAccessibilityService
import com.focusguard.ui.BlockNoticeActivity
import com.focusguard.utils.FocusGuardLogger

/**
 * Clears the task history of a blocked launchable app while the accessibility
 * curtain / block notice owns the foreground.
 *
 * Android does not allow a regular third-party app to force-stop arbitrary
 * packages. The strongest non-root equivalent available here is to restart the
 * target task at its launcher root with CLEAR_TASK and immediately restore the
 * FocusGuard block notice. The blocked process may still exist, but its previous
 * activity stack is discarded and the user cannot resume the protected screen.
 */
class BlockedAppTaskResetCoordinator(
    private val appContext: Context
) : Application.ActivityLifecycleCallbacks {

    private val mainHandler = Handler(Looper.getMainLooper())

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) {
        if (activity !is BlockNoticeActivity) return

        val noticeIntent = activity.intent ?: return
        val blockedPackage = noticeIntent.getStringExtra(
            BlockingAccessibilityService.EXTRA_BLOCKED_PACKAGE
        )
        val alreadyReset = noticeIntent.getBooleanExtra(EXTRA_BLOCKED_TASK_RESET_DONE, false)
        if (!shouldResetBlockedPackage(blockedPackage, appContext.packageName, alreadyReset)) {
            return
        }

        // Persist the marker on the current activity too, so configuration
        // recreation cannot reset the same task repeatedly.
        noticeIntent.putExtra(EXTRA_BLOCKED_TASK_RESET_DONE, true)

        val launchIntent = runCatching {
            appContext.packageManager.getLaunchIntentForPackage(blockedPackage!!)
        }.getOrNull()

        if (launchIntent == null) {
            FocusGuardLogger.log(
                "BlockedTaskReset",
                "Sem atividade inicial para limpar a task de $blockedPackage"
            )
            return
        }

        val resetIntent = prepareResetIntent(launchIntent)
        val restoreIntent = prepareRestoreIntent(noticeIntent, activity)

        runCatching {
            activity.startActivity(resetIntent)
            // Give ActivityTaskManager one main-loop turn to apply CLEAR_TASK,
            // then put the already-running notice back on top. The accessibility
            // curtain remains the visual barrier during this hand-off.
            mainHandler.post {
                runCatching { activity.startActivity(restoreIntent) }
                    .onFailure { error ->
                        FocusGuardLogger.logError(
                            "BlockedTaskReset",
                            "Falha ao restaurar tela de bloqueio após limpar $blockedPackage",
                            error
                        )
                        activity.startActivity(createHomeIntent())
                    }
            }
        }.onFailure { error ->
            FocusGuardLogger.logError(
                "BlockedTaskReset",
                "Falha ao limpar task do app bloqueado $blockedPackage",
                error
            )
        }
    }

    override fun onActivityStarted(activity: Activity) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivityStopped(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit

    companion object {
        internal const val EXTRA_BLOCKED_TASK_RESET_DONE =
            "com.focusguard.extra.BLOCKED_TASK_RESET_DONE"

        internal fun shouldResetBlockedPackage(
            blockedPackage: String?,
            focusGuardPackage: String,
            alreadyReset: Boolean
        ): Boolean = !alreadyReset &&
            !blockedPackage.isNullOrBlank() &&
            blockedPackage != focusGuardPackage

        internal fun prepareResetIntent(base: Intent): Intent = Intent(base).apply {
            addFlags(
                Intent.FLAG_ACTIVITY_NEW_TASK or
                    Intent.FLAG_ACTIVITY_CLEAR_TASK or
                    Intent.FLAG_ACTIVITY_NO_ANIMATION
            )
        }

        internal fun prepareRestoreIntent(source: Intent, activity: Activity): Intent =
            Intent(source).apply {
                setClass(activity, BlockNoticeActivity::class.java)
                putExtra(EXTRA_BLOCKED_TASK_RESET_DONE, true)
                addFlags(
                    Intent.FLAG_ACTIVITY_NEW_TASK or
                        Intent.FLAG_ACTIVITY_CLEAR_TOP or
                        Intent.FLAG_ACTIVITY_SINGLE_TOP or
                        Intent.FLAG_ACTIVITY_NO_ANIMATION or
                        Intent.FLAG_ACTIVITY_EXCLUDE_FROM_RECENTS
                )
            }

        internal fun createHomeIntent(): Intent = Intent(Intent.ACTION_MAIN).apply {
            addCategory(Intent.CATEGORY_HOME)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP)
        }
    }
}
