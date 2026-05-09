import os
import base64

def b64(text):
    return base64.b64encode(text.encode('utf-8')).decode('utf-8')

edits = [
    {
        'file': 'app/src/main/java/com/focusguard/ui/PomodoroLockActivity.kt',
        'q': b64("import android.view.KeyEvent\nimport android.view.WindowManager"),
        'c': b64("import android.view.KeyEvent\nimport android.view.View\nimport android.view.WindowManager\nimport androidx.core.view.WindowCompat\nimport androidx.core.view.WindowInsetsCompat\nimport androidx.core.view.WindowInsetsControllerCompat")
    },
    {
        'file': 'app/src/main/java/com/focusguard/ui/PomodoroLockActivity.kt',
        'q': b64("        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {\n            setShowWhenLocked(true)\n            setTurnScreenOn(true)\n        }"),
        'c': b64("        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {\n            setShowWhenLocked(true)\n            setTurnScreenOn(true)\n        }\n        enableImmersiveMode()")
    },
    {
        'file': 'app/src/main/java/com/focusguard/ui/PomodoroLockActivity.kt',
        'q': b64("    private fun enforceStrictLock() {"),
        'c': b64("    private fun enableImmersiveMode() {\n        WindowCompat.setDecorFitsSystemWindows(window, false)\n        WindowInsetsControllerCompat(window, window.decorView).let { controller ->\n            controller.hide(WindowInsetsCompat.Type.systemBars())\n            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE\n        }\n    }\n\n    private fun enforceStrictLock() {")
    },
    {
        'file': 'app/src/main/java/com/focusguard/ui/PomodoroLockActivity.kt',
        'q': b64("    override fun onWindowFocusChanged(hasFocus: Boolean) {\n        super.onWindowFocusChanged(hasFocus)\n        if (!hasFocus && StrictPomodoroLock.isActive(applicationContext)) {\n            // Janela perdeu foco (possível tentativa de abrir outra coisa)\n            enforceStrictLock()\n        }\n    }"),
        'c': b64("    override fun onWindowFocusChanged(hasFocus: Boolean) {\n        super.onWindowFocusChanged(hasFocus)\n        if (StrictPomodoroLock.isActive(applicationContext)) {\n            enableImmersiveMode()\n            if (!hasFocus) {\n                enforceStrictLock()\n            }\n        }\n    }")
    },
    {
        'file': 'app/src/main/java/com/focusguard/service/PomodoroForegroundService.kt',
        'q': b64("                } catch (e: Exception) {\n                    FocusGuardLogger.logError(\"PomodoroFGService\", \"Erro no loop watchdog\", e)\n                }\n                delay(2000)\n            }"),
        'c': b64("                } catch (e: Exception) {\n                    FocusGuardLogger.logError(\"PomodoroFGService\", \"Erro no loop watchdog\", e)\n                }\n                delay(300)\n            }")
    },
    {
        'file': 'app/src/main/java/com/focusguard/service/PomodoroForegroundService.kt',
        'q': b64("    private fun ensureLockActivityOnTop() {\n        try {\n            val intent = Intent(applicationContext, PomodoroLockActivity::class.java).apply {\n                addFlags(\n                    Intent.FLAG_ACTIVITY_NEW_TASK or\n                    Intent.FLAG_ACTIVITY_SINGLE_TOP or\n                    Intent.FLAG_ACTIVITY_CLEAR_TOP or\n                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT\n                )\n            }\n            applicationContext.startActivity(intent)\n        } catch (e: Exception) {\n            FocusGuardLogger.logError(\"PomodoroFGService\", \"Falha ao garantir LockActivity no topo\", e)\n        }\n    }"),
        'c': b64("    private fun ensureLockActivityOnTop() {\n        try {\n            val intent = Intent(applicationContext, PomodoroLockActivity::class.java).apply {\n                addFlags(\n                    Intent.FLAG_ACTIVITY_NEW_TASK or\n                    Intent.FLAG_ACTIVITY_SINGLE_TOP or\n                    Intent.FLAG_ACTIVITY_CLEAR_TOP or\n                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT\n                )\n            }\n            val pendingIntent = PendingIntent.getActivity(\n                applicationContext,\n                0,\n                intent,\n                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE\n            )\n            pendingIntent.send()\n        } catch (e: Exception) {\n            FocusGuardLogger.logError(\"PomodoroFGService\", \"Falha ao garantir LockActivity no topo via PendingIntent\", e)\n            try {\n                val fbIntent = Intent(applicationContext, PomodoroLockActivity::class.java).apply {\n                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)\n                }\n                applicationContext.startActivity(fbIntent)\n            } catch (_: Exception) {}\n        }\n    }")
    },
    {
        'file': 'app/src/main/java/com/focusguard/service/BlockingAccessibilityService.kt',
        'q': b64("            // Bloquear SystemUI (Recent Apps/Overview)\n            if (packageName == \"com.android.systemui\" && (className.contains(\"Recents\") || className.contains(\"Overview\"))) {\n                com.focusguard.utils.FocusGuardLogger.log(\"A11y\", \"Bloqueio Rigoroso: Recents interceptado\")\n                performGlobalAction(GLOBAL_ACTION_BACK)\n                launchPomodoroLockScreen()\n                return\n            }"),
        'c': b64("            // Bloquear SystemUI integralmente (Recents, Notification Shade, Quick Settings)\n            if (packageName == \"com.android.systemui\") {\n                com.focusguard.utils.FocusGuardLogger.log(\"A11y\", \"Bloqueio Rigoroso: SystemUI interceptado ($className)\")\n                performGlobalAction(GLOBAL_ACTION_HOME)\n                performGlobalAction(GLOBAL_ACTION_BACK)\n                launchPomodoroLockScreen()\n                return\n            }")
    },
    {
        'file': 'app/src/main/java/com/focusguard/service/BlockingAccessibilityService.kt',
        'q': b64("            com.focusguard.utils.FocusGuardLogger.log(\"A11y\", \"Bloqueio Rigoroso Pomodoro: $packageName impedido\")\n            blockApp(packageName)\n            return"),
        'c': b64("            com.focusguard.utils.FocusGuardLogger.log(\"A11y\", \"Bloqueio Rigoroso Pomodoro: $packageName impedido\")\n            performGlobalAction(GLOBAL_ACTION_HOME)\n            blockApp(packageName)\n            return")
    }
]

for idx, edit in enumerate(edits):
    print(f"Running edit {idx+1}/{len(edits)}")
    cmd = f"python ..\\toolkit.py rt -f \"{edit['file']}\" -q \"{edit['q']}\" -c \"{edit['c']}\" -B64 -Fuzzy"
    os.system(cmd)
print("Finished!")
