from pathlib import Path
import re

path = Path("app/src/main/java/com/focusguard/manager/BlockingSessionManager.kt")
text = path.read_text(encoding="utf-8")

# The manager itself must enforce the credential contract so a future caller cannot bypass UI.
start = text.index("    suspend fun startTimeSession(")
end = text.index("\n\n\n    /**\n     * Ativa", start)
block = text[start:end]
if 'ensureMasterCredentialFor("TIME")' not in block:
    block = block.replace(
        "            ensureBlockingPermissionsReady()\n",
        '            ensureBlockingPermissionsReady()\n            ensureMasterCredentialFor("TIME")\n',
        1,
    )

old_comment = '''            // Sem gate de senha mestre aqui de propósito: o jejum não tem saída por
            // credencial, então exigir uma seria pedir chave que não abre nada. O
            // que ele exige é consentimento informado, coletado na UI antes de
            // chegar até aqui — ver MasterCredentialPolicy.
            //
'''
new_comment = '''            // A regra é validada novamente no manager: a UI não é uma fronteira de
            // segurança. A senha mestre é a única saída antecipada da sessão TIME
            // inteira e por isso precisa existir antes de a sessão ser armada.
'''
block = block.replace(old_comment, new_comment, 1)
text = text[:start] + block + text[end:]

# The uninstall gate follows only IDs explicitly adopted by TimedBlockProtectionController.
if "import com.focusguard.security.TimedBlockProtectionController" not in text:
    text = text.replace(
        "import com.focusguard.security.SelfProtectionStateStore\n",
        "import com.focusguard.security.SelfProtectionStateStore\nimport com.focusguard.security.TimedBlockProtectionController\n",
        1,
    )

pattern = re.compile(
    r'''    /\*\*\n     \* Desinstalar só pode ser impedido.*?\n    val isUninstallBlockedByTimeFlow: Flow<Boolean> = combine\(.*?\n    \}\n\n    val hasRegisteredSessionFlow''',
    re.S,
)
replacement = '''    /**
     * Only explicit protected TIME ids are authoritative for uninstall gating.
     *
     * Recovery presets, time-hardened usage limits, PASSWORD and Pomodoro can use their own
     * blocking semantics without silently inheriting the package-removal commitment.
     */
    val isUninstallBlockedByTimeFlow: Flow<Boolean> = activeSessionsFlow.map { sessions ->
        val protectedIds = TimedBlockProtectionController.getInstance(context)
            .protectedSessionIdsSnapshot()
        sessions.any { session ->
            session.id in protectedIds &&
                session.sessionType.equals("TIME", ignoreCase = true) &&
                participatesInBlocking(session) &&
                isCurrentlyInBlockingWindow(session)
        }
    }

    val hasRegisteredSessionFlow'''
text, count = pattern.subn(replacement, text, count=1)
if count != 1:
    raise SystemExit(f"isUninstallBlockedByTimeFlow replacement expected once, got {count}")

path.write_text(text, encoding="utf-8")
