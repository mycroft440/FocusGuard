from pathlib import Path

screen_path = Path('app/src/main/java/com/focusguard/ui/compose/screens/FocusModeScreen.kt')
text = screen_path.read_text(encoding='utf-8')

text = text.replace(
    'var durationText by rememberSaveable { mutableStateOf("60") }',
    'var durationText by rememberSaveable { mutableStateOf("45") }',
    1,
)

call_old = '''            onGrayscaleEnabledChange = { grayscaleEnabled = it },
            onAddApps = { showAppPicker = true },
            isStarting = isStarting,'''
call_new = '''            onGrayscaleEnabledChange = { grayscaleEnabled = it },
            onAddApps = { showAppPicker = true },
            onApplyPreset = { amount, unit, packages ->
                durationText = amount
                durationUnit = unit
                selectedPackages = packages
                manager.saveDraftPackages(packages)
                startOutcome = null
            },
            isStarting = isStarting,'''
if call_old not in text:
    raise SystemExit('FocusModeSetupContent call site not found')
text = text.replace(call_old, call_new, 1)

start_marker = '@Composable\nprivate fun FocusModeSetupContent('
end_marker = '@Composable\nprivate fun FocusModeActiveContent('
start = text.find(start_marker)
end = text.find(end_marker, start)
if start < 0 or end < 0:
    raise SystemExit('FocusModeSetupContent block not found')

replacement = r'''@Composable
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
    onApplyPreset: (String, FocusModePolicy.DurationUnit, Set<String>) -> Unit,
    isStarting: Boolean,
    startOutcome: FocusModeManager.StartOutcome?,
    onStart: () -> Unit
) {
    val selectedApps = remember(apps, mandatoryPackages, selectedPackages) {
        apps.filter {
            it.packageName in selectedPackages && it.packageName !in mandatoryPackages
        }
    }
    val whatsappPackage = remember(apps) {
        apps.firstOrNull {
            it.appName.equals("WhatsApp", ignoreCase = true) ||
                it.packageName.contains("whatsapp", ignoreCase = true)
        }?.packageName
    }
    val spotifyPackage = remember(apps) {
        apps.firstOrNull {
            it.appName.equals("Spotify", ignoreCase = true) ||
                it.packageName.contains("spotify", ignoreCase = true)
        }?.packageName
    }
    val workPackages = remember(whatsappPackage) { setOfNotNull(whatsappPackage) }
    val studyPackages = remember { emptySet<String>() }
    val readingPackages = remember(spotifyPackage) { setOfNotNull(spotifyPackage) }

    val fixed45Selected = durationUnit == FocusModePolicy.DurationUnit.MINUTES &&
        durationText == "45"
    val fixed2hSelected = durationUnit == FocusModePolicy.DurationUnit.HOURS &&
        durationText == "2"
    val fixed5hSelected = durationUnit == FocusModePolicy.DurationUnit.HOURS &&
        durationText == "5"
    var customDurationOpen by rememberSaveable {
        mutableStateOf(!(fixed45Selected || fixed2hSelected || fixed5hSelected))
    }

    val durationLabel = when {
        fixed45Selected && !customDurationOpen ->
            stringResource(R.string.focus_mode_static_duration_45)
        fixed2hSelected && !customDurationOpen ->
            stringResource(R.string.focus_mode_static_duration_2h)
        fixed5hSelected && !customDurationOpen ->
            stringResource(R.string.focus_mode_static_duration_5h)
        else -> stringResource(
            R.string.focus_mode_static_custom_duration_value,
            durationText.ifBlank { "0" }
        )
    }
    val allowedSummary = when (selectedApps.size) {
        0 -> stringResource(R.string.focus_mode_static_no_extra_apps)
        1 -> stringResource(R.string.focus_mode_static_one_extra_app)
        else -> stringResource(
            R.string.focus_mode_static_many_extra_apps,
            selectedApps.size
        )
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        Text(
            text = stringResource(R.string.focus_mode_static_profiles),
            color = TextHint,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            val workDuration = stringResource(R.string.focus_mode_static_duration_2h)
            FocusPresetCard(
                title = stringResource(R.string.focus_mode_static_profile_work),
                meta = if (whatsappPackage != null) {
                    stringResource(
                        R.string.focus_mode_static_profile_meta_with_app,
                        workDuration,
                        "WhatsApp"
                    )
                } else {
                    stringResource(
                        R.string.focus_mode_static_profile_meta_no_app,
                        workDuration
                    )
                },
                selected = fixed2hSelected && !customDurationOpen &&
                    selectedPackages == workPackages,
                modifier = Modifier.weight(1f),
                onClick = {
                    customDurationOpen = false
                    onApplyPreset(
                        "2",
                        FocusModePolicy.DurationUnit.HOURS,
                        workPackages
                    )
                }
            )

            val studyDuration = stringResource(R.string.focus_mode_static_duration_45)
            FocusPresetCard(
                title = stringResource(R.string.focus_mode_static_profile_study),
                meta = stringResource(
                    R.string.focus_mode_static_profile_meta_no_app,
                    studyDuration
                ),
                selected = fixed45Selected && !customDurationOpen &&
                    selectedPackages == studyPackages,
                modifier = Modifier.weight(1f),
                onClick = {
                    customDurationOpen = false
                    onApplyPreset(
                        "45",
                        FocusModePolicy.DurationUnit.MINUTES,
                        studyPackages
                    )
                }
            )

            FocusPresetCard(
                title = stringResource(R.string.focus_mode_static_profile_reading),
                meta = if (spotifyPackage != null) {
                    stringResource(
                        R.string.focus_mode_static_profile_meta_with_app,
                        studyDuration,
                        "Spotify"
                    )
                } else {
                    stringResource(
                        R.string.focus_mode_static_profile_meta_no_app,
                        studyDuration
                    )
                },
                selected = fixed45Selected && !customDurationOpen &&
                    selectedPackages == readingPackages,
                modifier = Modifier.weight(1f),
                onClick = {
                    customDurationOpen = false
                    onApplyPreset(
                        "45",
                        FocusModePolicy.DurationUnit.MINUTES,
                        readingPackages
                    )
                }
            )
        }

        Text(
            text = stringResource(R.string.focus_mode_static_duration_section),
            color = TextHint,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(top = 2.dp)
        )

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(7.dp)
        ) {
            FocusDurationChip(
                label = stringResource(R.string.focus_mode_static_duration_45),
                selected = fixed45Selected && !customDurationOpen,
                modifier = Modifier.weight(1f),
                onClick = {
                    customDurationOpen = false
                    onDurationUnitChange(FocusModePolicy.DurationUnit.MINUTES)
                    onDurationTextChange("45")
                }
            )
            FocusDurationChip(
                label = stringResource(R.string.focus_mode_static_duration_2h),
                selected = fixed2hSelected && !customDurationOpen,
                modifier = Modifier.weight(1f),
                onClick = {
                    customDurationOpen = false
                    onDurationUnitChange(FocusModePolicy.DurationUnit.HOURS)
                    onDurationTextChange("2")
                }
            )
            FocusDurationChip(
                label = stringResource(R.string.focus_mode_static_duration_5h),
                selected = fixed5hSelected && !customDurationOpen,
                modifier = Modifier.weight(1f),
                onClick = {
                    customDurationOpen = false
                    onDurationUnitChange(FocusModePolicy.DurationUnit.HOURS)
                    onDurationTextChange("5")
                }
            )
            FocusDurationChip(
                label = stringResource(R.string.focus_mode_static_other),
                selected = customDurationOpen,
                modifier = Modifier.weight(1f),
                onClick = {
                    val wasFixed = fixed45Selected || fixed2hSelected || fixed5hSelected
                    customDurationOpen = true
                    onDurationUnitChange(FocusModePolicy.DurationUnit.MINUTES)
                    if (wasFixed) onDurationTextChange("60")
                }
            )
        }

        if (customDurationOpen) {
            Row(
                modifier = Modifier.fillMaxWidth().height(52.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                OutlinedTextField(
                    value = durationText,
                    onValueChange = onDurationTextChange,
                    modifier = Modifier.width(112.dp),
                    singleLine = true,
                    isError = durationText.isNotBlank() && !durationValid,
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number)
                )
                Text(
                    text = stringResource(R.string.focus_mode_static_custom_minutes),
                    color = TextHint,
                    fontSize = 13.sp
                )
                if (durationText.isNotBlank() && !durationValid) {
                    Text(
                        text = stringResource(R.string.focus_mode_duration_invalid),
                        color = MaterialTheme.colorScheme.error,
                        fontSize = 10.sp,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
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
                    .padding(horizontal = 14.dp, vertical = 11.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.fg_focus_grayscale),
                        color = TextPrimary,
                        fontWeight = FontWeight.Bold,
                        fontSize = 14.sp
                    )
                    Text(
                        text = stringResource(R.string.focus_mode_static_grayscale_desc),
                        color = TextHint,
                        fontSize = 11.sp,
                        lineHeight = 15.sp,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }
                Switch(checked = grayscaleEnabled, onCheckedChange = null)
            }
        }

        Text(
            text = stringResource(R.string.focus_mode_static_allowed_section),
            color = TextHint,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 1.sp,
            modifier = Modifier.padding(top = 2.dp)
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
                    modifier = Modifier.size(32.dp)
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
                    modifier = Modifier.size(32.dp)
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

        Spacer(modifier = Modifier.weight(1f))

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

        Text(
            text = stringResource(
                R.string.focus_mode_static_dock_summary,
                durationLabel,
                allowedSummary
            ),
            color = TextHint,
            fontSize = 11.sp,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth()
        )
    }
}

@Composable
private fun FocusPresetCard(
    title: String,
    meta: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .height(76.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) {
                AccentCyan.copy(alpha = 0.10f)
            } else {
                DarkCard
            }
        ),
        border = BorderStroke(
            1.dp,
            if (selected) AccentCyan else CardBorder
        ),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 10.dp, vertical = 9.dp),
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                color = TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = meta,
                color = TextHint,
                fontSize = 10.5.sp,
                lineHeight = 14.sp,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(top = 3.dp)
            )
        }
    }
}

@Composable
private fun FocusDurationChip(
    label: String,
    selected: Boolean,
    modifier: Modifier = Modifier,
    onClick: () -> Unit
) {
    Card(
        modifier = modifier
            .height(44.dp)
            .clickable(onClick = onClick),
        colors = CardDefaults.cardColors(
            containerColor = if (selected) AccentCyan else DarkCard
        ),
        border = BorderStroke(
            1.dp,
            if (selected) AccentCyan else CardBorder
        ),
        shape = RoundedCornerShape(24.dp)
    ) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text(
                text = label,
                color = if (selected) MaterialTheme.colorScheme.onPrimary else TextPrimary,
                fontWeight = FontWeight.Bold,
                fontSize = 13.sp,
                textAlign = TextAlign.Center
            )
        }
    }
}

@Composable
private fun FocusPrototypeAppTile(
    label: String,
    tag: String,
    modifier: Modifier = Modifier,
    onClick: (() -> Unit)? = null,
    icon: @Composable () -> Unit
) {
    Card(
        modifier = modifier
            .height(88.dp)
            .then(
                if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier
            ),
        colors = CardDefaults.cardColors(containerColor = DarkCard),
        border = BorderStroke(1.dp, CardBorder),
        shape = RoundedCornerShape(14.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxSize().padding(horizontal = 7.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            icon()
            Spacer(Modifier.height(4.dp))
            Text(
                text = label,
                color = TextPrimary,
                fontWeight = FontWeight.SemiBold,
                fontSize = 11.sp,
                textAlign = TextAlign.Center,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            if (tag.isNotBlank()) {
                Text(
                    text = tag,
                    color = TextHint,
                    fontSize = 9.5.sp,
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

@Composable
private fun FocusPrototypeSelectedAppsTile(
    selectedApps: List<FocusModeSelectableApp>,
    isLoading: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val label = when {
        isLoading -> stringResource(R.string.focus_mode_static_apps)
        selectedApps.isEmpty() -> stringResource(R.string.focus_mode_add_apps)
        selectedApps.size == 1 -> selectedApps.first().appName
        else -> stringResource(
            R.string.focus_mode_static_apps_count,
            selectedApps.size
        )
    }
    val tag = when {
        isLoading -> stringResource(R.string.focus_mode_static_loading)
        selectedApps.isEmpty() -> stringResource(R.string.focus_mode_static_tap_choose)
        selectedApps.size == 1 -> stringResource(R.string.focus_mode_static_selected)
        else -> stringResource(R.string.focus_mode_static_tap_edit)
    }

    FocusPrototypeAppTile(
        label = label,
        tag = tag,
        modifier = modifier,
        onClick = if (isLoading) null else onClick
    ) {
        when {
            isLoading -> CircularProgressIndicator(
                color = AccentCyan,
                strokeWidth = 2.dp,
                modifier = Modifier.size(28.dp)
            )
            selectedApps.isEmpty() -> Icon(
                Icons.Default.Add,
                contentDescription = null,
                tint = AccentCyan,
                modifier = Modifier.size(32.dp)
            )
            selectedApps.size == 1 -> InstalledAppIcon(
                packageName = selectedApps.first().packageName,
                appName = selectedApps.first().appName
            )
            else -> Icon(
                Icons.Default.Apps,
                contentDescription = null,
                tint = AccentCyan,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

'''

text = text[:start] + replacement + text[end:]
screen_path.write_text(text, encoding='utf-8')

resources = {
    Path('app/src/main/res/values/focus_mode_static_strings.xml'): '''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="focus_mode_static_profiles">PROFILES</string>
    <string name="focus_mode_static_profile_work">Work</string>
    <string name="focus_mode_static_profile_study">Study</string>
    <string name="focus_mode_static_profile_reading">Reading</string>
    <string name="focus_mode_static_profile_meta_with_app">%1$s · %2$s</string>
    <string name="focus_mode_static_profile_meta_no_app">%1$s · no app</string>
    <string name="focus_mode_static_duration_section">DURATION</string>
    <string name="focus_mode_static_duration_45">45 min</string>
    <string name="focus_mode_static_duration_2h">2 h</string>
    <string name="focus_mode_static_duration_5h">5 h</string>
    <string name="focus_mode_static_other">Other</string>
    <string name="focus_mode_static_custom_minutes">custom minutes</string>
    <string name="focus_mode_static_custom_duration_value">%1$s min</string>
    <string name="focus_mode_static_grayscale_desc">Turns the phone black and white during focus — colorless screens pull less attention.</string>
    <string name="focus_mode_static_allowed_section">ALLOWED DURING FOCUS</string>
    <string name="focus_mode_static_phone">Phone</string>
    <string name="focus_mode_static_sms">SMS</string>
    <string name="focus_mode_static_always">always</string>
    <string name="focus_mode_static_apps">Apps</string>
    <string name="focus_mode_static_apps_count">%1$d apps</string>
    <string name="focus_mode_static_loading">loading</string>
    <string name="focus_mode_static_tap_choose">tap to choose</string>
    <string name="focus_mode_static_selected">selected</string>
    <string name="focus_mode_static_tap_edit">tap to edit</string>
    <string name="focus_mode_static_no_extra_apps">no extra app allowed</string>
    <string name="focus_mode_static_one_extra_app">1 app allowed</string>
    <string name="focus_mode_static_many_extra_apps">%1$d apps allowed</string>
    <string name="focus_mode_static_dock_summary">%1$s · %2$s</string>
</resources>
''',
    Path('app/src/main/res/values-en/focus_mode_static_strings.xml'): '''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="focus_mode_static_profiles">PROFILES</string>
    <string name="focus_mode_static_profile_work">Work</string>
    <string name="focus_mode_static_profile_study">Study</string>
    <string name="focus_mode_static_profile_reading">Reading</string>
    <string name="focus_mode_static_profile_meta_with_app">%1$s · %2$s</string>
    <string name="focus_mode_static_profile_meta_no_app">%1$s · no app</string>
    <string name="focus_mode_static_duration_section">DURATION</string>
    <string name="focus_mode_static_duration_45">45 min</string>
    <string name="focus_mode_static_duration_2h">2 h</string>
    <string name="focus_mode_static_duration_5h">5 h</string>
    <string name="focus_mode_static_other">Other</string>
    <string name="focus_mode_static_custom_minutes">custom minutes</string>
    <string name="focus_mode_static_custom_duration_value">%1$s min</string>
    <string name="focus_mode_static_grayscale_desc">Turns the phone black and white during focus — colorless screens pull less attention.</string>
    <string name="focus_mode_static_allowed_section">ALLOWED DURING FOCUS</string>
    <string name="focus_mode_static_phone">Phone</string>
    <string name="focus_mode_static_sms">SMS</string>
    <string name="focus_mode_static_always">always</string>
    <string name="focus_mode_static_apps">Apps</string>
    <string name="focus_mode_static_apps_count">%1$d apps</string>
    <string name="focus_mode_static_loading">loading</string>
    <string name="focus_mode_static_tap_choose">tap to choose</string>
    <string name="focus_mode_static_selected">selected</string>
    <string name="focus_mode_static_tap_edit">tap to edit</string>
    <string name="focus_mode_static_no_extra_apps">no extra app allowed</string>
    <string name="focus_mode_static_one_extra_app">1 app allowed</string>
    <string name="focus_mode_static_many_extra_apps">%1$d apps allowed</string>
    <string name="focus_mode_static_dock_summary">%1$s · %2$s</string>
</resources>
''',
    Path('app/src/main/res/values-pt/focus_mode_static_strings.xml'): '''<?xml version="1.0" encoding="utf-8"?>
<resources>
    <string name="focus_mode_static_profiles">PERFIS</string>
    <string name="focus_mode_static_profile_work">Trabalho</string>
    <string name="focus_mode_static_profile_study">Estudo</string>
    <string name="focus_mode_static_profile_reading">Leitura</string>
    <string name="focus_mode_static_profile_meta_with_app">%1$s · %2$s</string>
    <string name="focus_mode_static_profile_meta_no_app">%1$s · nenhum app</string>
    <string name="focus_mode_static_duration_section">DURAÇÃO</string>
    <string name="focus_mode_static_duration_45">45 min</string>
    <string name="focus_mode_static_duration_2h">2 h</string>
    <string name="focus_mode_static_duration_5h">5 h</string>
    <string name="focus_mode_static_other">Outro</string>
    <string name="focus_mode_static_custom_minutes">minutos personalizados</string>
    <string name="focus_mode_static_custom_duration_value">%1$s min</string>
    <string name="focus_mode_static_grayscale_desc">Deixa o celular em preto e branco durante o foco — telas sem cor puxam menos a atenção.</string>
    <string name="focus_mode_static_allowed_section">LIBERADOS DURANTE O FOCO</string>
    <string name="focus_mode_static_phone">Ligação</string>
    <string name="focus_mode_static_sms">SMS</string>
    <string name="focus_mode_static_always">sempre</string>
    <string name="focus_mode_static_apps">Apps</string>
    <string name="focus_mode_static_apps_count">%1$d apps</string>
    <string name="focus_mode_static_loading">carregando</string>
    <string name="focus_mode_static_tap_choose">toque para escolher</string>
    <string name="focus_mode_static_selected">você escolheu</string>
    <string name="focus_mode_static_tap_edit">toque para editar</string>
    <string name="focus_mode_static_no_extra_apps">nenhum app extra liberado</string>
    <string name="focus_mode_static_one_extra_app">1 app liberado</string>
    <string name="focus_mode_static_many_extra_apps">%1$d apps liberados</string>
    <string name="focus_mode_static_dock_summary">%1$s · %2$s</string>
</resources>
''',
}

for path, content in resources.items():
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(content, encoding='utf-8')
