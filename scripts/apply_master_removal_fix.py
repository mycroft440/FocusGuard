from pathlib import Path
import subprocess


def read(path: str) -> str:
    return Path(path).read_text()


def write(path: str, text: str) -> None:
    Path(path).write_text(text)


def replace_once(path: str, old: str, new: str) -> None:
    text = read(path)
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{path}: expected one match, got {count}: {old!r}")
    write(path, text.replace(old, new, 1))


def insert_before(path: str, anchor: str, addition: str) -> None:
    replace_once(path, anchor, addition + anchor)


def insert_after(path: str, anchor: str, addition: str) -> None:
    replace_once(path, anchor, anchor + addition)


# Restore only the exact AntiPorn files from the previously approved branch.
subprocess.run(
    [
        "git", "fetch", "origin",
        "feat/time-block-master-revoke:refs/remotes/origin/feat/time-block-master-revoke",
    ],
    check=True,
)
subprocess.run(
    [
        "git", "checkout", "origin/feat/time-block-master-revoke", "--",
        "app/src/main/java/com/focusguard/ui/compose/screens/RecoveryCourseGatewayScreen.kt",
        "app/src/main/res/values/strings_recovery_course.xml",
        "app/src/main/res/values-en/strings_recovery_course.xml",
        "app/src/main/res/values-pt/strings_recovery_course.xml",
    ],
    check=True,
)

# ---------------------------------------------------------------------------
# ManagedSelfProtectionPolicy: exact Samsung gateway + App Info labels.
# ---------------------------------------------------------------------------
path = "app/src/main/java/com/focusguard/security/ManagedSelfProtectionPolicy.kt"
insert_after(path, "        val deviceAdmin: Boolean,\n", "        val appInfoGateway: Boolean,\n")
insert_after(
    path,
    '        "Apps do administrador do aparelho",\n',
    '        "Apps administradores do sistema",\n'
    '        "Aplicativos administradores do sistema",\n'
    '        "System admin apps",\n',
)
insert_before(
    path,
    "    internal val focusGuardSearchTerms = listOf(\n",
    "    internal val appInfoGatewaySearchTerms = listOf(\n"
    '        "Informações do aplicativo",\n'
    '        "Informações do app",\n'
    '        "App info",\n'
    '        "Application info",\n'
    '        "Información de la aplicación",\n'
    '        "Información de app"\n'
    "    )\n\n",
)
insert_before(
    path,
    "    private val normalizedFocusGuardSearchTerms = focusGuardSearchTerms.map(::normalize)\n",
    "    private val normalizedAppInfoGatewaySearchTerms =\n"
    "        appInfoGatewaySearchTerms.map(::normalize)\n",
)
insert_after(
    path,
    "            deviceAdmin = matchesDeviceAdmin(normalizedValues),\n",
    "            appInfoGateway = valuesContainAnyNormalized(\n"
    "                normalizedValues,\n"
    "                normalizedAppInfoGatewaySearchTerms\n"
    "            ),\n",
)
insert_before(
    path,
    "    fun textTargetsFocusGuard(values: Iterable<CharSequence?>): Boolean =\n",
    "    fun textTargetsAppInfoGateway(values: Iterable<CharSequence?>): Boolean =\n"
    "        valuesContainAnyNormalized(normalizeValues(values), normalizedAppInfoGatewaySearchTerms)\n\n",
)

# ---------------------------------------------------------------------------
# Settings policy: App Info is protected, master exit wins explicit clicks.
# ---------------------------------------------------------------------------
path = "app/src/main/java/com/focusguard/security/SettingsInterceptionPolicy.kt"
replace_once(
    path,
    "        val textMentionsEssentialSpecialAccess: Boolean\n",
    "        val textMentionsEssentialSpecialAccess: Boolean,\n"
    "        val textMentionsAppInfoGateway: Boolean = false\n",
)
replace_once(path, "        if (strictPomodoroActive) return Decision.POMODORO_LOCK\n\n", "")
insert_before(
    path,
    "        // The two menus below are revocation gateways. Once a protection is active,\n",
    "        // App Info is a removal gateway on Samsung/One UI. Blocking the row itself\n"
    "        // avoids waiting for the destination Activity to become visible.\n"
    "        if (signals.isViewClickedEvent && signals.textMentionsAppInfoGateway) {\n"
    "            return Decision.PROTECT_AND_ARM_GUARD\n"
    "        }\n\n",
)
insert_before(
    path,
    "        if (signals.guardArmed && !signals.isViewClickedEvent) {\n",
    "        // Explicit removal/permission clicks must reach the master-password gate\n"
    "        // even while strict Pomodoro owns ordinary Settings navigation.\n"
    "        if (strictPomodoroActive) return Decision.POMODORO_LOCK\n\n",
)

# ---------------------------------------------------------------------------
# Accessibility service: preserve instant block, then show master gate once.
# ---------------------------------------------------------------------------
path = "app/src/main/java/com/focusguard/service/BlockingAccessibilityService.kt"
insert_after(path, "import com.focusguard.ui.BlockNoticeActivity\n", "import com.focusguard.ui.MasterRemovalActivity\n")
insert_after(path, "    private var lastBlockNoticeLaunchElapsed = 0L\n", "    private var lastMasterRemovalGateLaunchElapsed = 0L\n")
replace_once(
    path,
    '    private val clickInterceptionSearchTerms =\n        (directClickContextSufficientTerms + "admin").distinct()\n',
    '    private val clickInterceptionSearchTerms =\n'
    '        (directClickContextSufficientTerms +\n'
    '            listOf("admin", "Informações do app", "Informações do aplicativo", "App info"))\n'
    '            .distinct()\n',
)
replace_once(
    path,
    "        if (!isSystemUi && isPomodoroStrictActive) {\n",
    "        if (!isSystemUi &&\n"
    "            isPomodoroStrictActive &&\n"
    "            event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED\n"
    "        ) {\n",
)
replace_once(
    path,
    "            textMentionsEssentialSpecialAccess = managedTextSignals.essentialSpecialAccess\n",
    "            textMentionsEssentialSpecialAccess = managedTextSignals.essentialSpecialAccess,\n"
    "            textMentionsAppInfoGateway = managedTextSignals.appInfoGateway\n",
)
insert_before(
    path,
    "        val decision = SettingsInterceptionPolicy.decide(\n",
    "        val masterRemovalTarget = if (event.eventType == AccessibilityEvent.TYPE_VIEW_CLICKED) {\n"
    "            when {\n"
    "                signals.textMentionsDeviceAdmin || classTargetsDeviceAdmin ->\n"
    "                    MasterRemovalActivity.Target.DEVICE_ADMIN\n"
    "                (signals.textMentionsInstalledAccessibilityApps && signals.textMentionsAccessibility) ||\n"
    "                    classTargetsAccessibilityServiceToggle ||\n"
    "                    (signals.textMentionsAccessibilityDisclosure && signals.textMentionsFocusGuard) ->\n"
    "                    MasterRemovalActivity.Target.ACCESSIBILITY\n"
    "                signals.textMentionsAppInfoGateway || classTargetsAppDetails ->\n"
    "                    MasterRemovalActivity.Target.APP_INFO\n"
    "                classTargetsUninstall ||\n"
    "                    packageName in SettingsInterceptionPolicy.packageInstallerPackages ||\n"
    "                    (signals.textMentionsDestructiveControl && signals.textMentionsFocusGuard) ->\n"
    "                    MasterRemovalActivity.Target.UNINSTALL\n"
    "                signals.textMentionsFocusGuard -> MasterRemovalActivity.Target.APP_INFO\n"
    "                else -> null\n"
    "            }\n"
    "        } else {\n"
    "            null\n"
    "        }\n\n",
)
replace_once(
    path,
    "            SettingsInterceptionPolicy.Decision.PROTECT -> {\n"
    "                executeProtectionAction(eventDetectedAtNanos)\n"
    "                true\n"
    "            }\n",
    "            SettingsInterceptionPolicy.Decision.PROTECT -> {\n"
    "                executeProtectionAction(eventDetectedAtNanos)\n"
    "                masterRemovalTarget?.let(::launchMasterRemovalGate)\n"
    "                true\n"
    "            }\n",
)
replace_once(
    path,
    "                executeProtectionAction(eventDetectedAtNanos)\n"
    "                true\n"
    "            }\n"
    "        }\n"
    "    }\n\n"
    "    private fun eventTextValues",
    "                executeProtectionAction(eventDetectedAtNanos)\n"
    "                masterRemovalTarget?.let(::launchMasterRemovalGate)\n"
    "                true\n"
    "            }\n"
    "        }\n"
    "    }\n\n"
    "    private fun launchMasterRemovalGate(target: MasterRemovalActivity.Target) {\n"
    "        val nowElapsed = SystemClock.elapsedRealtime()\n"
    "        if (nowElapsed - lastMasterRemovalGateLaunchElapsed < MASTER_REMOVAL_GATE_COOLDOWN_MILLIS) return\n"
    "        lastMasterRemovalGateLaunchElapsed = nowElapsed\n\n"
    "        val intent = MasterRemovalActivity.createIntent(this, target).apply {\n"
    "            addFlags(\n"
    "                Intent.FLAG_ACTIVITY_NEW_TASK or\n"
    "                    Intent.FLAG_ACTIVITY_CLEAR_TOP or\n"
    "                    Intent.FLAG_ACTIVITY_SINGLE_TOP or\n"
    "                    Intent.FLAG_ACTIVITY_NO_ANIMATION\n"
    "            )\n"
    "        }\n"
    "        runCatching {\n"
    "            startActivity(intent)\n"
    "            // Keep the system screen covered until our own private credential UI\n"
    "            // is requested, then remove the overlay so it cannot eat password input.\n"
    "            dismissInstantBlockCurtain()\n"
    "        }.onFailure { error ->\n"
    "            FocusGuardLogger.logError(\"MasterRemoval\", \"Falha ao abrir senha mestre\", error)\n"
    "        }\n"
    "    }\n\n"
    "    private fun eventTextValues",
)
insert_after(
    path,
    "        private const val FOCUS_MODE_REDIRECT_COOLDOWN_MILLIS = 600L\n",
    "        private const val MASTER_REMOVAL_GATE_COOLDOWN_MILLIS = 1_200L\n",
)

# ---------------------------------------------------------------------------
# Manifest: private, non-exported gate.
# ---------------------------------------------------------------------------
path = "app/src/main/AndroidManifest.xml"
insert_before(
    path,
    "        <activity\n            android:name=\".ui.BlockNoticeActivity\"\n",
    "        <activity\n"
    "            android:name=\".ui.MasterRemovalActivity\"\n"
    "            android:excludeFromRecents=\"true\"\n"
    "            android:exported=\"false\"\n"
    "            android:launchMode=\"singleTop\"\n"
    "            android:noHistory=\"true\"\n"
    "            android:taskAffinity=\"com.focusguard.masterremoval\"\n"
    "            android:theme=\"@style/Theme.FocusGuard\" />\n\n",
)

# ---------------------------------------------------------------------------
# Master removal Activity: verify existing master credential, remove all blocks,
# release admin/A11y protections, then reopen the Android surface requested.
# ---------------------------------------------------------------------------
activity = r'''package com.focusguard.ui

import android.app.AlertDialog
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.text.InputType
import android.view.View
import android.view.ViewGroup
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.TextView
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.focusguard.R
import com.focusguard.admin.DeviceOwnerManager
import com.focusguard.manager.BlockingSessionManager
import com.focusguard.security.AuthenticatedRemovalWindow
import com.focusguard.security.DeactivationCredentialManager
import com.focusguard.service.BlockingAccessibilityService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class MasterRemovalActivity : ComponentActivity() {

    enum class Target { APP_INFO, DEVICE_ADMIN, ACCESSIBILITY, UNINSTALL }

    private lateinit var credentialManager: DeactivationCredentialManager
    private lateinit var sessionManager: BlockingSessionManager
    private lateinit var deviceOwnerManager: DeviceOwnerManager
    private lateinit var passwordField: EditText
    private lateinit var errorText: TextView
    private var working = false

    private val target: Target by lazy {
        runCatching { Target.valueOf(intent.getStringExtra(EXTRA_TARGET).orEmpty()) }
            .getOrDefault(Target.APP_INFO)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        credentialManager = DeactivationCredentialManager(applicationContext)
        sessionManager = BlockingSessionManager.getInstance(applicationContext)
        deviceOwnerManager = DeviceOwnerManager.getInstance(applicationContext)
        showCredentialDialog()
    }

    private fun showCredentialDialog() {
        val density = resources.displayMetrics.density
        val container = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((24 * density).toInt(), (6 * density).toInt(), (24 * density).toInt(), 0)
        }
        passwordField = EditText(this).apply {
            hint = getString(R.string.master_removal_password_hint)
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            isSingleLine = true
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            )
        }
        errorText = TextView(this).apply {
            setTextColor(getColor(android.R.color.holo_red_dark))
            visibility = View.GONE
        }
        container.addView(passwordField)
        container.addView(errorText)

        val dialog = AlertDialog.Builder(this)
            .setTitle(R.string.master_removal_title)
            .setMessage(R.string.master_removal_description)
            .setView(container)
            .setNegativeButton(R.string.cancel) { _, _ -> finish() }
            .setPositiveButton(R.string.master_removal_confirm, null)
            .setOnCancelListener { finish() }
            .create()

        dialog.setOnShowListener {
            dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
                authorizeAndRelease(dialog)
            }
            passwordField.requestFocus()
        }
        dialog.setCanceledOnTouchOutside(false)
        dialog.show()
    }

    private fun authorizeAndRelease(dialog: AlertDialog) {
        if (working) return
        val credential = passwordField.text?.toString().orEmpty()
        if (credential.isBlank()) return
        working = true
        passwordField.isEnabled = false
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = false
        errorText.visibility = View.GONE

        lifecycleScope.launch {
            val verified = withContext(Dispatchers.Default) {
                when (credentialManager.verify(credential)) {
                    DeactivationCredentialManager.VerificationResult.PASSWORD_ACCEPTED,
                    DeactivationCredentialManager.VerificationResult.RECOVERY_ACCEPTED -> true
                    DeactivationCredentialManager.VerificationResult.REJECTED,
                    DeactivationCredentialManager.VerificationResult.NOT_CONFIGURED -> false
                }
            }
            if (!verified) {
                showError(getString(R.string.master_removal_wrong_password), dialog)
                return@launch
            }

            if (!sessionManager.removeAllBlocksForDevelopmentExit()) {
                showError(getString(R.string.master_removal_release_failed), dialog)
                return@launch
            }

            AuthenticatedRemovalWindow.open(applicationContext)
            if (!deviceOwnerManager.releaseRemovalProtectionForDevelopmentExit()) {
                AuthenticatedRemovalWindow.close(applicationContext)
                showError(getString(R.string.master_removal_release_failed), dialog)
                return@launch
            }

            sendBroadcast(
                BlockingAccessibilityService.createDevelopmentRelinquishIntent(applicationContext)
            )

            if (!openRequestedAndroidSurface()) {
                showError(getString(R.string.master_removal_open_failed), dialog)
                return@launch
            }
            dialog.dismiss()
            finish()
        }
    }

    private fun showError(message: String, dialog: AlertDialog) {
        working = false
        passwordField.isEnabled = true
        passwordField.text?.clear()
        errorText.text = message
        errorText.visibility = View.VISIBLE
        dialog.getButton(AlertDialog.BUTTON_POSITIVE).isEnabled = true
        passwordField.requestFocus()
    }

    private fun openRequestedAndroidSurface(): Boolean = runCatching {
        when (target) {
            Target.APP_INFO -> startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                    data = Uri.parse("package:$packageName")
                }
            )
            Target.DEVICE_ADMIN -> {
                if (!deviceOwnerManager.openDeviceAdminSettings(this)) {
                    error("Device Admin settings unavailable")
                }
            }
            Target.ACCESSIBILITY -> startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
            Target.UNINSTALL -> startActivity(
                Intent(Intent.ACTION_DELETE).apply { data = Uri.parse("package:$packageName") }
            )
        }
        true
    }.getOrDefault(false)

    companion object {
        private const val EXTRA_TARGET = "MASTER_REMOVAL_TARGET"

        fun createIntent(context: Context, target: Target): Intent =
            Intent(context, MasterRemovalActivity::class.java).apply {
                putExtra(EXTRA_TARGET, target.name)
            }
    }
}
'''
Path("app/src/main/java/com/focusguard/ui/MasterRemovalActivity.kt").write_text(activity)

# Dedicated strings keep this repair isolated from AntiPorn/resources unrelated to it.
Path("app/src/main/res/values/strings_master_removal.xml").write_text(r'''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="master_removal_title">Master password required</string>
    <string name="master_removal_description">This Android screen can remove HardBlock or a permission that keeps active protections working. Enter the master password to remove all blocks and release the protections before continuing.</string>
    <string name="master_removal_password_hint">Master password</string>
    <string name="master_removal_confirm">Unlock and continue</string>
    <string name="master_removal_wrong_password">Incorrect master password.</string>
    <string name="master_removal_release_failed">HardBlock could not safely release every protection. Nothing was opened.</string>
    <string name="master_removal_open_failed">Protections were released, but Android could not open the requested screen.</string>
</resources>
''')
Path("app/src/main/res/values-en/strings_master_removal.xml").write_text(
    Path("app/src/main/res/values/strings_master_removal.xml").read_text()
)
Path("app/src/main/res/values-pt/strings_master_removal.xml").write_text(r'''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="master_removal_title">Senha mestre necessária</string>
    <string name="master_removal_description">Esta tela do Android pode remover o HardBlock ou uma permissão que mantém as proteções ativas. Digite a senha mestre para remover todos os bloqueios e liberar as proteções antes de continuar.</string>
    <string name="master_removal_password_hint">Senha mestre</string>
    <string name="master_removal_confirm">Desbloquear e continuar</string>
    <string name="master_removal_wrong_password">Senha mestre incorreta.</string>
    <string name="master_removal_release_failed">O HardBlock não conseguiu liberar todas as proteções com segurança. Nenhuma tela foi aberta.</string>
    <string name="master_removal_open_failed">As proteções foram liberadas, mas o Android não conseguiu abrir a tela solicitada.</string>
</resources>
''')

# Tests.
path = "app/src/test/java/com/focusguard/security/SettingsInterceptionPolicyTest.kt"
replace_once(
    path,
    "        textMentionsEssentialSpecialAccess: Boolean = false\n",
    "        textMentionsEssentialSpecialAccess: Boolean = false,\n"
    "        textMentionsAppInfoGateway: Boolean = false\n",
)
replace_once(
    path,
    "        textMentionsEssentialSpecialAccess = textMentionsEssentialSpecialAccess\n",
    "        textMentionsEssentialSpecialAccess = textMentionsEssentialSpecialAccess,\n"
    "        textMentionsAppInfoGateway = textMentionsAppInfoGateway\n",
)
insert_before(
    path,
    "    @Test\n    fun `strict pomodoro still owns settings while its device lock is active`() {\n",
    "    @Test\n"
    "    fun `app info gateway is blocked before navigation`() {\n"
    "        assertThat(\n"
    "            decide(signals(isViewClickedEvent = true, textMentionsAppInfoGateway = true))\n"
    "        ).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)\n"
    "    }\n\n"
    "    @Test\n"
    "    fun `explicit app info gateway wins over strict pomodoro for master exit`() {\n"
    "        assertThat(\n"
    "            decide(\n"
    "                signals(isViewClickedEvent = true, textMentionsAppInfoGateway = true),\n"
    "                strictPomodoro = true\n"
    "            )\n"
    "        ).isEqualTo(Decision.PROTECT_AND_ARM_GUARD)\n"
    "    }\n\n",
)

path = "app/src/test/java/com/focusguard/security/ManagedSelfProtectionPolicyTest.kt"
text = read(path)
idx = text.rfind("\n}")
if idx < 0:
    raise SystemExit("ManagedSelfProtectionPolicyTest closing brace missing")
extra = r'''

    @Test
    fun `Samsung system administrator label targets device admin`() {
        assertThat(
            ManagedSelfProtectionPolicy.textTargetsDeviceAdmin(
                listOf("Apps administradores do sistema")
            )
        ).isTrue()
    }

    @Test
    fun `app information gateway labels are recognized`() {
        assertThat(
            ManagedSelfProtectionPolicy.textTargetsAppInfoGateway(
                listOf("Informações do aplicativo")
            )
        ).isTrue()
        assertThat(ManagedSelfProtectionPolicy.textTargetsAppInfoGateway(listOf("App info")))
            .isTrue()
    }
'''
write(path, text[:idx] + extra + text[idx:])

# Plan: record the requested restoration and master exit only.
path = ".agents/implementation_plan.md"
text = read(path)
if "Restaurar exatamente o AntiPorn fixo sem rolagem" not in text:
    text += r'''

## Correção de regressão — senha mestre e AntiPorn
- CUMPRIDO: Restaurar exatamente o AntiPorn fixo sem rolagem e com frases rotativas da versão aprovada.
- CUMPRIDO: reconhecer “Informações do aplicativo” e “Apps administradores do sistema” como gateways protegidos.
- CUMPRIDO: ao tentar remover/desinstalar/perder permissões, bloquear instantaneamente e abrir o gate da senha mestre.
- CUMPRIDO: senha mestre autorizada remove todas as fontes de bloqueio e libera Device Owner/Device Admin/Acessibilidade antes de devolver o usuário ao Android.
- PRÓXIMO OBJETIVO: validar testes, lint, APK/AAB Release e APK Debug antes da integração.
'''
    write(path, text)
