from pathlib import Path


def once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 match, found {count}")
    return text.replace(old, new, 1)


def between(text: str, start: str, end: str, replacement: str, label: str) -> str:
    start_index = text.find(start)
    if start_index < 0:
        raise RuntimeError(f"{label}: start marker missing")
    end_index = text.find(end, start_index)
    if end_index < 0:
        raise RuntimeError(f"{label}: end marker missing")
    return text[:start_index] + replacement + text[end_index:]


# ============================================================================
# 1. MODO FOCO
# ============================================================================
focus_path = Path("app/src/main/java/com/focusguard/ui/compose/screens/FocusModeScreen.kt")
focus = focus_path.read_text()

imports = [
    (
        "import androidx.compose.foundation.BorderStroke\n",
        "import androidx.compose.foundation.BorderStroke\nimport androidx.compose.foundation.background\n",
        "import androidx.compose.foundation.background",
    ),
    (
        "import androidx.compose.foundation.layout.width\n",
        "import androidx.compose.foundation.layout.width\nimport androidx.compose.foundation.rememberScrollState\n",
        "import androidx.compose.foundation.rememberScrollState",
    ),
    (
        "import androidx.compose.foundation.selection.toggleable\n",
        "import androidx.compose.foundation.selection.toggleable\nimport androidx.compose.foundation.verticalScroll\n",
        "import androidx.compose.foundation.verticalScroll",
    ),
    (
        "import androidx.compose.ui.graphics.ImageBitmap\n",
        "import androidx.compose.ui.graphics.Color\nimport androidx.compose.ui.graphics.ImageBitmap\n",
        "import androidx.compose.ui.graphics.Color",
    ),
    (
        "import com.focusguard.ui.compose.theme.TextPrimary\n",
        "import com.focusguard.ui.compose.theme.TextPrimary\nimport com.focusguard.ui.compose.theme.TextSecondary\n",
        "import com.focusguard.ui.compose.theme.TextSecondary",
    ),
]
for old, new, added in imports:
    if added not in focus:
        focus = once(focus, old, new, f"Focus import {added}")

new_focus_setup = r'''@Composable
private fun FocusModeSetupContent(
    apps: List<FocusModeSelectableApp>,
    mandatoryPackages: Set<String>,
    isLoadingApps: Boolean,
    selectedPackages: Set<String>,
    durationText: String,
    onDurationTextChange: (String) -> Unit,
    durationUnit: FocusModePolicy.DurationUnit,
    onDurationUnitChange: (FocusModePolicy.DurationUnit) -> Unit,
    durationValid: Boolean,
    grayscaleEnabled: Boolean,
    onGrayscaleEnabledChange: (Boolean) -> Unit,
    onAddApps: () -> Unit,
    isStarting: Boolean,
    startOutcome: FocusModeManager.StartOutcome?,
    onStart: () -> Unit
) {
    val selectedApps = remember(apps, mandatoryPackages, selectedPackages) {
        apps.filter {
            it.packageName in selectedPackages && it.packageName !in mandatoryPackages
        }
    }
    val initialMinutes = when (durationUnit) {
        FocusModePolicy.DurationUnit.MINUTES -> durationText.toIntOrNull() ?: 40
        FocusModePolicy.DurationUnit.HOURS -> (durationText.toIntOrNull() ?: 1) * 60
        FocusModePolicy.DurationUnit.DAYS -> (durationText.toIntOrNull() ?: 0) * 24 * 60
    }.coerceIn(1, FOCUS_DURATION_MAX_MINUTES)
    var sliderMinutes by rememberSaveable { mutableIntStateOf(initialMinutes) }
    var showHowItWorks by rememberSaveable { mutableStateOf(false) }

    val hoursUnit = stringResource(R.string.focus_mode_static_hours_short)
    val minutesUnit = stringResource(R.string.focus_mode_static_minutes_short)
    val durationLabel = when {
        sliderMinutes < 60 -> "$sliderMinutes $minutesUnit"
        sliderMinutes % 60 == 0 -> "${sliderMinutes / 60} $hoursUnit"
        else -> "${sliderMinutes / 60} $hoursUnit ${sliderMinutes % 60} $minutesUnit"
    }
    val allowedSummary = when (selectedApps.size) {
        0 -> stringResource(R.string.focus_mode_static_no_extra_apps)
        1 -> stringResource(R.string.focus_mode_static_one_extra_app)
        else -> stringResource(R.string.focus_mode_static_many_extra_apps, selectedApps.size)
    }
    val howTitle = stringResource(R.string.focus_mode_how_it_works)

    Box(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 16.dp, vertical = 6.dp)
        ) {
            Column(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(9.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.focus_mode_compact_purpose),
                        color = TextHint,
                        fontSize = 13.sp,
                        lineHeight = 18.sp,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(onClick = { showHowItWorks = true }) {
                        Text(
                            text = howTitle,
                            color = TextSecondary,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.SemiBold
                        )
                        Icon(
                            Icons.Default.ArrowDropDown,
                            contentDescription = null,
                            tint = TextHint,
                            modifier = Modifier.size(18.dp)
                        )
                    }
                }

                Text(
                    text = stringResource(R.string.focus_mode_static_duration_section),
                    color = TextHint,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.5.sp,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )
                Text(
                    text = durationLabel,
                    color = TextPrimary,
                    fontSize = 46.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.align(Alignment.CenterHorizontally)
                )

                Slider(
                    value = sliderMinutes.toFloat(),
                    onValueChange = { raw ->
                        val next = raw.roundToInt().coerceIn(1, FOCUS_DURATION_MAX_MINUTES)
                        if (next != sliderMinutes) {
                            sliderMinutes = next
                            onDurationUnitChange(FocusModePolicy.DurationUnit.MINUTES)
                            onDurationTextChange(next.toString())
                        }
                    },
                    valueRange = 1f..FOCUS_DURATION_MAX_MINUTES.toFloat(),
                    colors = SliderDefaults.colors(
                        thumbColor = AccentCyan,
                        activeTrackColor = AccentCyan,
                        inactiveTrackColor = CardBorder
                    )
                )
                Row(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 4.dp),
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Text("1 $minutesUnit", color = TextHint, fontSize = 10.sp)
                    Text("8 $hoursUnit", color = TextHint, fontSize = 10.sp)
                }

                Card(
                    modifier = Modifier
                        .fillMaxWidth()
                        .toggleable(
                            value = grayscaleEnabled,
                            role = Role.Switch,
                            onValueChange = onGrayscaleEnabledChange
                        ),
                    colors = CardDefaults.cardColors(containerColor = DarkCard),
                    border = BorderStroke(1.dp, CardBorder),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 14.dp, vertical = 5.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.fg_focus_grayscale),
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold,
                            fontSize = 14.sp,
                            modifier = Modifier.weight(1f)
                        )
                        Switch(checked = grayscaleEnabled, onCheckedChange = null)
                    }
                }

                Text(
                    text = stringResource(R.string.focus_mode_static_allowed_section),
                    color = TextHint,
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    FocusPrototypeAppTile(
                        label = stringResource(R.string.focus_mode_static_phone),
                        tag = stringResource(R.string.focus_mode_static_always),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.Phone,
                            contentDescription = null,
                            tint = TextHint,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                    FocusPrototypeAppTile(
                        label = stringResource(R.string.focus_mode_static_sms),
                        tag = stringResource(R.string.focus_mode_static_always),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(
                            Icons.Default.Message,
                            contentDescription = null,
                            tint = TextPrimary,
                            modifier = Modifier.size(30.dp)
                        )
                    }
                    FocusPrototypeSelectedAppsTile(
                        selectedApps = selectedApps,
                        isLoading = isLoadingApps,
                        onClick = onAddApps,
                        modifier = Modifier.weight(1f)
                    )
                }

                if (startOutcome != null) {
                    FocusModeStartError(startOutcome)
                }
                Spacer(modifier = Modifier.height(8.dp))
            }

            Text(
                text = stringResource(
                    R.string.focus_mode_static_dock_summary,
                    durationLabel,
                    allowedSummary
                ),
                color = TextHint,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 5.dp)
            )

            Button(
                onClick = onStart,
                enabled = !isStarting && durationValid && !isLoadingApps,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = AccentCyan),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (isStarting) {
                    CircularProgressIndicator(
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp,
                        modifier = Modifier.size(20.dp)
                    )
                } else {
                    Text(
                        stringResource(R.string.focus_mode_start),
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp
                    )
                }
            }
        }

        if (showHowItWorks) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Color.Black.copy(alpha = 0.72f))
                    .clickable { showHowItWorks = false }
                    .padding(24.dp),
                contentAlignment = Alignment.Center
            ) {
                Card(
                    colors = CardDefaults.cardColors(containerColor = DarkCard),
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
                            color = TextHint,
                            fontSize = 11.sp
                        )
                    }
                }
            }
        }
    }
}

'''

focus = between(
    focus,
    "@Composable\nprivate fun FocusModeSetupContent(",
    "@Composable\nprivate fun FocusDurationChip(",
    new_focus_setup,
    "FocusModeSetupContent",
)
focus = between(
    focus,
    "@Composable\nprivate fun FocusDurationChip(",
    "@Composable\nprivate fun FocusPrototypeAppTile(",
    "",
    "remove FocusDurationChip",
)
focus = focus.replace("private const val FOCUS_DURATION_STEP_MINUTES = 20\n", "")
focus = focus.replace(
    "private const val FOCUS_DURATION_SLIDER_STEPS = FOCUS_DURATION_MAX_MINUTES /\n"
    "    FOCUS_DURATION_STEP_MINUTES - 1\n",
    "",
)
focus_path.write_text(focus)

for path, content in {
    "app/src/main/res/values/focus_mode_pending_strings.xml": """<?xml version=\"1.0\" encoding=\"utf-8\"?>
<resources>
    <string name=\"focus_mode_compact_purpose\">Blocks the phone and releases only what you choose.</string>
    <string name=\"focus_mode_how_it_works\">How it works</string>
    <string name=\"focus_mode_tap_anywhere_to_close\">Tap anywhere to close.</string>
</resources>
""",
    "app/src/main/res/values-en/focus_mode_pending_strings.xml": """<?xml version=\"1.0\" encoding=\"utf-8\"?>
<resources>
    <string name=\"focus_mode_compact_purpose\">Blocks the phone and releases only what you choose.</string>
    <string name=\"focus_mode_how_it_works\">How it works</string>
    <string name=\"focus_mode_tap_anywhere_to_close\">Tap anywhere to close.</string>
</resources>
""",
    "app/src/main/res/values-pt/focus_mode_pending_strings.xml": """<?xml version=\"1.0\" encoding=\"utf-8\"?>
<resources>
    <string name=\"focus_mode_compact_purpose\">Bloqueia o telefone e libera só o que você escolher.</string>
    <string name=\"focus_mode_how_it_works\">Como funciona</string>
    <string name=\"focus_mode_tap_anywhere_to_close\">Toque em qualquer área da tela para fechar.</string>
</resources>
""",
}.items():
    Path(path).write_text(content)


# ============================================================================
# 2. LIMITES DE USO: HORAS + MINUTOS
# ============================================================================
usage_path = Path("app/src/main/java/com/focusguard/ui/compose/screens/UsageBlockConfigScreen.kt")
usage = usage_path.read_text()
usage = once(
    usage,
    "    val dailyLimitHours: Int = 0,\n",
    "    val dailyLimitHours: Int = 0,\n    val dailyLimitMinutes: Int = 0,\n",
    "BlockConfig minute field",
)
usage = once(
    usage,
    '    var dailyLimitHoursText by remember { mutableStateOf("") }\n',
    '    var dailyLimitHoursText by remember { mutableStateOf("") }\n'
    '    var dailyLimitMinutesText by remember { mutableStateOf("") }\n',
    "minute input state",
)
usage = once(
    usage,
    "    val dailyLimitHours = dailyLimitHoursText.toIntOrNull() ?: 0\n"
    "    val daysToBlock = (daysToBlockText.toIntOrNull() ?: 0).coerceIn(0, 120)\n",
    "    val dailyLimitHours = (dailyLimitHoursText.toIntOrNull() ?: 0).coerceIn(0, 24)\n"
    "    val dailyLimitMinutes = (dailyLimitMinutesText.toIntOrNull() ?: 0).coerceIn(0, 59)\n"
    "    val dailyLimitTotalMinutes = dailyLimitHours * 60 + dailyLimitMinutes\n"
    "    val daysToBlock = (daysToBlockText.toIntOrNull() ?: 0).coerceIn(0, 120)\n",
    "calculate total daily minutes",
)
usage = once(
    usage,
    "    val canSave = dailyLimitHours > 0 && when (selectedType) {\n",
    "    val canSave = dailyLimitTotalMinutes in 1..(24 * 60) && when (selectedType) {\n",
    "validate total daily minutes",
)

old_hours_field = '''                    OutlinedTextField(
                        value = dailyLimitHoursText,
                        onValueChange = { value -> dailyLimitHoursText = value.filter { it.isDigit() } },
                        modifier = Modifier.fillMaxWidth(),
                        placeholder = { Text(stringResource(R.string.ex_2), color = TextHint) },
                        singleLine = true
                    )
'''
new_hours_minutes_fields = '''                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        OutlinedTextField(
                            value = dailyLimitHoursText,
                            onValueChange = { value ->
                                val digits = value.filter(Char::isDigit).take(2)
                                if (digits.isEmpty() || (digits.toIntOrNull() ?: 0) <= 24) {
                                    dailyLimitHoursText = digits
                                }
                            },
                            modifier = Modifier.weight(1f),
                            label = { Text(stringResource(R.string.limits_time_hours_short)) },
                            placeholder = { Text("0", color = TextHint) },
                            singleLine = true
                        )
                        OutlinedTextField(
                            value = dailyLimitMinutesText,
                            onValueChange = { value ->
                                val digits = value.filter(Char::isDigit).take(2)
                                if (digits.isEmpty() || (digits.toIntOrNull() ?: 0) <= 59) {
                                    dailyLimitMinutesText = digits
                                }
                            },
                            modifier = Modifier.weight(1f),
                            label = { Text(stringResource(R.string.limits_time_minutes_short)) },
                            placeholder = { Text("0", color = TextHint) },
                            singleLine = true
                        )
                    }
'''
usage = once(usage, old_hours_field, new_hours_minutes_fields, "replace hours-only input")
usage = once(
    usage,
    "                                dailyLimitHours = dailyLimitHours,\n",
    "                                dailyLimitHours = dailyLimitHours,\n"
    "                                dailyLimitMinutes = dailyLimitMinutes,\n",
    "save minute part",
)
usage_path.write_text(usage)

time_path = Path("app/src/main/java/com/focusguard/ui/compose/screens/TimeSessionConfigScreen.kt")
time = time_path.read_text()
time = once(
    time,
    "                val dailyLimitMinutes = (config.dailyLimitHours.coerceAtLeast(1) * 60)\n",
    "                val dailyLimitMinutes = (\n"
    "                    config.dailyLimitHours.coerceIn(0, 24) * 60 +\n"
    "                        config.dailyLimitMinutes.coerceIn(0, 59)\n"
    "                    ).coerceIn(1, 24 * 60)\n",
    "persist hours plus minutes",
)
time_path.write_text(time)

for path, content in {
    "app/src/main/res/values/usage_limit_time_strings.xml": """<?xml version=\"1.0\" encoding=\"utf-8\"?>
<resources>
    <string name=\"limits_time_hours_short\">Hours</string>
    <string name=\"limits_time_minutes_short\">Minutes</string>
</resources>
""",
    "app/src/main/res/values-en/usage_limit_time_strings.xml": """<?xml version=\"1.0\" encoding=\"utf-8\"?>
<resources>
    <string name=\"limits_time_hours_short\">Hours</string>
    <string name=\"limits_time_minutes_short\">Minutes</string>
</resources>
""",
    "app/src/main/res/values-pt/usage_limit_time_strings.xml": """<?xml version=\"1.0\" encoding=\"utf-8\"?>
<resources>
    <string name=\"limits_time_hours_short\">Horas</string>
    <string name=\"limits_time_minutes_short\">Minutos</string>
</resources>
""",
}.items():
    Path(path).write_text(content)


# ============================================================================
# 3. BLOQUEIO DE SITES: IMEDIATO E DINÂMICO ENTRE NAVEGADORES
# ============================================================================
service_path = Path("app/src/main/java/com/focusguard/service/BlockingAccessibilityService.kt")
service = service_path.read_text()
service = once(
    service,
    "    private val browserDebounceMillis = 120L\n",
    "    private val browserDebounceMillis = 0L\n",
    "browser debounce",
)
service = once(
    service,
    "    private val websiteBlockCooldownMillis = 1_500L\n",
    "",
    "remove website cooldown",
)
service = once(
    service,
    "    private val websitePulseMillis = 5_000L\n",
    "    private val websitePulseMillis = 1_000L\n",
    "website pulse",
)

service = once(
    service,
    '''                    if (packageName in browserPackages &&
                        (fastEvent || now - lastBrowserCheck >= browserDebounceMillis)
                    ) {
                        lastBrowserCheck = now
                        handleBrowserEvent(event)
                    }
''',
    '''                    if (isRecognizedBrowserSurface(event, packageName) &&
                        (fastEvent || now - lastBrowserCheck >= browserDebounceMillis)
                    ) {
                        lastBrowserCheck = now
                        handleBrowserEvent(event, packageName)
                    }
''',
    "browser content fast path",
)

service = once(
    service,
    '''            packageName in browserPackages &&
                (blockedWebsitesDomainSet.isNotEmpty() || limitedWebsiteDomains.isNotEmpty()) ->
                handleBrowserEvent(event)
''',
    '''            isRecognizedBrowserSurface(event, packageName) &&
                (blockedWebsitesDomainSet.isNotEmpty() || limitedWebsiteDomains.isNotEmpty()) ->
                handleBrowserEvent(event, packageName)
''',
    "browser window fast path",
)

service = once(
    service,
    '''    private fun handleBrowserEvent(event: AccessibilityEvent) {
        val packageName = event.packageName?.toString() ?: return
        if (packageName !in browserPackages) return
''',
    '''    private fun handleBrowserEvent(
        event: AccessibilityEvent,
        resolvedPackageName: String
    ) {
        val packageName = resolvedPackageName.takeIf(String::isNotBlank) ?: return
        if (!isRecognizedBrowserSurface(event, packageName)) return
''',
    "resolved browser package",
)

browser_helper = '''    /**
     * Dynamic browser recognition. All installed HTTPS handlers are already in
     * [browserPackages]; this fallback promotes an unknown browser/WebView shell
     * as soon as it exposes a genuine address-bar/URI node to accessibility.
     */
    private fun isRecognizedBrowserSurface(
        event: AccessibilityEvent,
        packageName: String
    ): Boolean {
        if (packageName.isBlank()) return false
        if (packageName in browserPackages) return true

        if (WebsiteBlocker.extractAddressBarTextFromEvent(event, packageName) != null) {
            browserPackages = browserPackages + packageName
            return true
        }

        val canInspectRoot = event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_WINDOW_CONTENT_CHANGED ||
            event.eventType == AccessibilityEvent.TYPE_VIEW_FOCUSED
        if (!canInspectRoot) return false

        val root = rootInActiveWindow ?: sourceNodeForEvent(event) ?: return false
        val recognized = try {
            WebsiteBlocker.extractAddressBarTextFromRoot(root, packageName) != null
        } finally {
            recycleSafely(root)
        }
        if (recognized) browserPackages = browserPackages + packageName
        return recognized
    }

'''
if "private fun isRecognizedBrowserSurface(" not in service:
    marker = "    private fun handleBrowserEvent(\n"
    index = service.find(marker)
    if index < 0:
        raise RuntimeError("browser helper insertion marker missing")
    service = service[:index] + browser_helper + service[index:]

service = once(
    service,
    '''    private fun beginWebsiteBlock(domain: String, packageName: String): Boolean {
        val now = System.currentTimeMillis()
        val blockKey = "$packageName|$domain"
        if (blockKey == lastWebsiteBlockKey &&
            now - lastWebsiteBlockTime < websiteBlockCooldownMillis
        ) return false

        lastWebsiteBlockKey = blockKey
        lastWebsiteBlockTime = now
        stopWebsiteTracking(now)
        return true
    }
''',
    '''    private fun beginWebsiteBlock(domain: String, packageName: String): Boolean {
        val now = System.currentTimeMillis()
        // Match app blocking: every attempt renews enforcement. Reload, Back and
        // forward navigation must never slip through an old cooldown window.
        lastWebsiteBlockKey = "$packageName|$domain"
        lastWebsiteBlockTime = now
        stopWebsiteTracking(now)
        return true
    }
''',
    "repeat website enforcement",
)
service_path.write_text(service)


doc_path = Path("docs/WEBSITE_BLOCKING.md")
doc = doc_path.read_text()
old_doc = """   - É a camada comum para qualquer navegador instalado que declare suporte a
     links HTTPS, além de uma lista de compatibilidade para os navegadores mais
     usados (Firefox, Brave, Samsung Internet, Opera, Vivaldi e DuckDuckGo).
"""
new_doc = """   - É a camada comum para qualquer navegador instalado que declare suporte a
     links HTTPS. Um navegador desconhecido também passa a ser reconhecido
     dinamicamente assim que expõe uma barra de endereço ou campo URI à
     acessibilidade; a lista conhecida fica apenas como fallback.
"""
if old_doc in doc:
    doc = doc.replace(old_doc, new_doc, 1)
doc_path.write_text(doc)

Path("docs/EXECUTION_PLAN_2026-08-27.md").write_text(
    """# Plano de execução — 27/08/2026

## Implementado nesta rodada

- Modo foco: botão **Iniciar** fixo na parte inferior e conteúdo superior rolável.
- Modo foco: sugestões/atalhos de duração removidos; duração centralizada no slider.
- Modo foco: **Tela cinza** reduzida a título + chave.
- Modo foco: **Como funciona** abre um card sobre a tela e qualquer toque fecha o card.
- Limites de uso: fluxo antigo de horas passou a aceitar **horas + minutos** e persiste o total em minutos para apps e sites.
- Bloqueio de sites: usa o pacote já resolvido do evento, inclusive em `TYPE_WINDOWS_CHANGED`.
- Bloqueio de sites: todos os handlers HTTPS são detectados e navegadores desconhecidos podem ser promovidos pela barra de URL acessível.
- Bloqueio de sites: debounce removido e tentativas repetidas deixaram de ser ignoradas por cooldown.
- Limites de sites: pulso de contabilização reduzido de 5 s para 1 s.

## Limite técnico documentado

Sem VPN/proxy/extensão, Android não permite que um app comum leia HTTPS de um navegador que esconda completamente a URL da acessibilidade. Chrome/Edge em Device Owner continuam cobertos adicionalmente por política nativa `URLBlocklist`.

## Próximo bloco

- Revisar atualização contínua e dimensões do widget Pomodoro.
- Validar manualmente Chrome, Edge, Firefox, Brave, Samsung Internet, Opera, Vivaldi, DuckDuckGo e pelo menos um navegador não listado.
"""
)
