import json
import base64

def b64(text):
    return base64.b64encode(text.encode('utf-8')).decode('utf-8')

plan = {
    'operations': [
        {
            'action': 'rt',
            'file': 'app/src/main/java/com/focusguard/ui/PomodoroLockActivity.kt',
            'findTextB64': b64("import android.view.KeyEvent\r\nimport android.view.WindowManager"),
            'newContentB64': b64("import android.view.KeyEvent\r\nimport android.view.View\r\nimport android.view.WindowManager\r\nimport androidx.core.view.WindowCompat\r\nimport androidx.core.view.WindowInsetsCompat\r\nimport androidx.core.view.WindowInsetsControllerCompat"),
            'fuzzy': True
        },
        {
            'action': 'rt',
            'file': 'app/src/main/java/com/focusguard/ui/PomodoroLockActivity.kt',
            'findTextB64': b64("        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {\r\n            setShowWhenLocked(true)\r\n            setTurnScreenOn(true)\r\n        }"),
            'newContentB64': b64("        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {\r\n            setShowWhenLocked(true)\r\n            setTurnScreenOn(true)\r\n        }\r\n        enableImmersiveMode()"),
            'fuzzy': True
        },
        {
            'action': 'rt',
            'file': 'app/src/main/java/com/focusguard/ui/PomodoroLockActivity.kt',
            'findTextB64': b64("    private fun enforceStrictLock() {"),
            'newContentB64': b64("    private fun enableImmersiveMode() {\r\n        WindowCompat.setDecorFitsSystemWindows(window, false)\r\n        WindowInsetsControllerCompat(window, window.decorView).let { controller ->\r\n            controller.hide(WindowInsetsCompat.Type.systemBars())\r\n            controller.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE\r\n        }\r\n    }\r\n\r\n    private fun enforceStrictLock() {"),
            'fuzzy': True
        },
        {
            'action': 'rt',
            'file': 'app/src/main/java/com/focusguard/ui/PomodoroLockActivity.kt',
            'findTextB64': b64("    override fun onWindowFocusChanged(hasFocus: Boolean) {\r\n        super.onWindowFocusChanged(hasFocus)\r\n        if (!hasFocus && StrictPomodoroLock.isActive(applicationContext)) {\r\n            // Janela perdeu foco (possível tentativa de abrir outra coisa)\r\n            enforceStrictLock()\r\n        }\r\n    }"),
            'newContentB64': b64("    override fun onWindowFocusChanged(hasFocus: Boolean) {\r\n        super.onWindowFocusChanged(hasFocus)\r\n        if (StrictPomodoroLock.isActive(applicationContext)) {\r\n            enableImmersiveMode()\r\n            if (!hasFocus) {\r\n                enforceStrictLock()\r\n            }\r\n        }\r\n    }"),
            'fuzzy': True
        },
        {
            'action': 'rt',
            'file': 'app/src/main/java/com/focusguard/service/PomodoroForegroundService.kt',
            'findTextB64': b64("                } catch (e: Exception) {\r\n                    FocusGuardLogger.logError(\"PomodoroFGService\", \"Erro no loop watchdog\", e)\r\n                }\r\n                delay(2000)\r\n            }"),
            'newContentB64': b64("                } catch (e: Exception) {\r\n                    FocusGuardLogger.logError(\"PomodoroFGService\", \"Erro no loop watchdog\", e)\r\n                }\r\n                delay(300)\r\n            }"),
            'fuzzy': True
        },
        {
            'action': 'rt',
            'file': 'app/src/main/java/com/focusguard/service/PomodoroForegroundService.kt',
            'findTextB64': b64("    private fun ensureLockActivityOnTop() {\r\n        try {\r\n            val intent = Intent(applicationContext, PomodoroLockActivity::class.java).apply {\r\n                addFlags(\r\n                    Intent.FLAG_ACTIVITY_NEW_TASK or\r\n                    Intent.FLAG_ACTIVITY_SINGLE_TOP or\r\n                    Intent.FLAG_ACTIVITY_CLEAR_TOP or\r\n                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT\r\n                )\r\n            }\r\n            applicationContext.startActivity(intent)\r\n        } catch (e: Exception) {\r\n            FocusGuardLogger.logError(\"PomodoroFGService\", \"Falha ao garantir LockActivity no topo\", e)\r\n        }\r\n    }"),
            'newContentB64': b64("    private fun ensureLockActivityOnTop() {\r\n        try {\r\n            val intent = Intent(applicationContext, PomodoroLockActivity::class.java).apply {\r\n                addFlags(\r\n                    Intent.FLAG_ACTIVITY_NEW_TASK or\r\n                    Intent.FLAG_ACTIVITY_SINGLE_TOP or\r\n                    Intent.FLAG_ACTIVITY_CLEAR_TOP or\r\n                    Intent.FLAG_ACTIVITY_REORDER_TO_FRONT\r\n                )\r\n            }\r\n            val pendingIntent = PendingIntent.getActivity(\r\n                applicationContext,\r\n                0,\r\n                intent,\r\n                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE\r\n            )\r\n            pendingIntent.send()\r\n        } catch (e: Exception) {\r\n            FocusGuardLogger.logError(\"PomodoroFGService\", \"Falha ao garantir LockActivity no topo via PendingIntent\", e)\r\n            try {\r\n                val fbIntent = Intent(applicationContext, PomodoroLockActivity::class.java).apply {\r\n                    addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP)\r\n                }\r\n                applicationContext.startActivity(fbIntent)\r\n            } catch (_: Exception) {}\r\n        }\r\n    }"),
            'fuzzy': True
        },
        {
            'action': 'rt',
            'file': 'app/src/main/java/com/focusguard/service/BlockingAccessibilityService.kt',
            'findTextB64': b64("            // Bloquear SystemUI (Recent Apps/Overview)\r\n            if (packageName == \"com.android.systemui\" && (className.contains(\"Recents\") || className.contains(\"Overview\"))) {\r\n                com.focusguard.utils.FocusGuardLogger.log(\"A11y\", \"Bloqueio Rigoroso: Recents interceptado\")\r\n                performGlobalAction(GLOBAL_ACTION_BACK)\r\n                launchPomodoroLockScreen()\r\n                return\r\n            }"),
            'newContentB64': b64("            // Bloquear SystemUI integralmente (Recents, Notification Shade, Quick Settings)\r\n            if (packageName == \"com.android.systemui\") {\r\n                com.focusguard.utils.FocusGuardLogger.log(\"A11y\", \"Bloqueio Rigoroso: SystemUI interceptado ($className)\")\r\n                performGlobalAction(GLOBAL_ACTION_HOME)\r\n                performGlobalAction(GLOBAL_ACTION_BACK)\r\n                launchPomodoroLockScreen()\r\n                return\r\n            }"),
            'fuzzy': True
        },
        {
            'action': 'rt',
            'file': 'app/src/main/java/com/focusguard/service/BlockingAccessibilityService.kt',
            'findTextB64': b64("            com.focusguard.utils.FocusGuardLogger.log(\"A11y\", \"Bloqueio Rigoroso Pomodoro: $packageName impedido\")\r\n            blockApp(packageName)\r\n            return"),
            'newContentB64': b64("            com.focusguard.utils.FocusGuardLogger.log(\"A11y\", \"Bloqueio Rigoroso Pomodoro: $packageName impedido\")\r\n            performGlobalAction(GLOBAL_ACTION_HOME)\r\n            blockApp(packageName)\r\n            return"),
            'fuzzy': True
        }
    ]
}

with open('plan.json', 'w', encoding='utf-8') as f:
    json.dump(plan, f, ensure_ascii=False, indent=2)
print('plan.json gerado.')
