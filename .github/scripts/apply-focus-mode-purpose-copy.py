from pathlib import Path

screen_path = Path('app/src/main/java/com/focusguard/ui/compose/screens/FocusModeScreen.kt')
text = screen_path.read_text(encoding='utf-8')

# Remove preset callback from the call site.
text = text.replace('''            onAddApps = { showAppPicker = true },
            onApplyPreset = { amount, unit, packages ->
                durationText = amount
                durationUnit = unit
                selectedPackages = packages
                manager.saveDraftPackages(packages)
                startOutcome = null
            },
            isStarting = isStarting,''', '''            onAddApps = { showAppPicker = true },
            isStarting = isStarting,''', 1)

# Remove preset callback parameter and preset package discovery.
text = text.replace('''    onGrayscaleEnabledChange: (Boolean) -> Unit,
    onAddApps: () -> Unit,
    onApplyPreset: (String, FocusModePolicy.DurationUnit, Set<String>) -> Unit,
    isStarting: Boolean,''', '''    onGrayscaleEnabledChange: (Boolean) -> Unit,
    onAddApps: () -> Unit,
    isStarting: Boolean,''', 1)

preset_state_start = text.index('''    val whatsappPackage = remember(apps) {''')
preset_state_end = text.index('''    val fixed45Selected =''', preset_state_start)
text = text[:preset_state_start] + text[preset_state_end:]

# Replace the visual preset section with an explanation card.
visual_start = text.index('''        Text(
            text = stringResource(R.string.focus_mode_static_profiles),''')
visual_end = text.index('''        Text(
            text = stringResource(R.string.focus_mode_static_duration_section),''', visual_start)
new_visual = '''        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(containerColor = DarkCard),
            border = BorderStroke(1.dp, AccentCyan.copy(alpha = 0.35f)),
            shape = RoundedCornerShape(14.dp)
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 14.dp, vertical = 12.dp),
                verticalArrangement = Arrangement.spacedBy(5.dp)
            ) {
                Text(
                    text = stringResource(R.string.focus_mode_static_purpose_title),
                    color = TextPrimary,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold
                )
                Text(
                    text = stringResource(R.string.focus_mode_static_purpose_body),
                    color = TextHint,
                    fontSize = 12.sp,
                    lineHeight = 17.sp
                )
            }
        }

'''
text = text[:visual_start] + new_visual + text[visual_end:]

# Remove now-unused preset card composable.
preset_fun_start = text.find('@Composable\nprivate fun FocusPresetCard(')
if preset_fun_start >= 0:
    preset_fun_end = text.find('@Composable\nprivate fun FocusDurationChip(', preset_fun_start)
    if preset_fun_end < 0:
        raise SystemExit('FocusDurationChip marker not found')
    text = text[:preset_fun_start] + text[preset_fun_end:]

screen_path.write_text(text, encoding='utf-8')

updates = {
    Path('app/src/main/res/values/focus_mode_static_strings.xml'): (
        '<string name="focus_mode_static_profiles">PROFILES</string>',
        '<string name="focus_mode_static_purpose_title">What is Focus Mode for?</string>\n'
        '    <string name="focus_mode_static_purpose_body">Completely block access to your phone so technology addiction cannot keep feeding itself. Meanwhile, work or study with a phone that only releases the essential apps you need at that moment.</string>'
    ),
    Path('app/src/main/res/values-en/focus_mode_static_strings.xml'): (
        '<string name="focus_mode_static_profiles">PROFILES</string>',
        '<string name="focus_mode_static_purpose_title">What is Focus Mode for?</string>\n'
        '    <string name="focus_mode_static_purpose_body">Completely block access to your phone so technology addiction cannot keep feeding itself. Meanwhile, work or study with a phone that only releases the essential apps you need at that moment.</string>'
    ),
    Path('app/src/main/res/values-pt/focus_mode_static_strings.xml'): (
        '<string name="focus_mode_static_profiles">PERFIS</string>',
        '<string name="focus_mode_static_purpose_title">Para que serve o Modo foco?</string>\n'
        '    <string name="focus_mode_static_purpose_body">Bloqueie completamente o acesso ao seu telefone impedindo que o vício em tecnologia se sustente. Enquanto isso, trabalhe ou estude com um telefone que só vai liberar apps essenciais para uso naquele momento.</string>'
    ),
}

for path, (old, new) in updates.items():
    content = path.read_text(encoding='utf-8')
    if old not in content:
        raise SystemExit(f'marker not found in {path}')
    path.write_text(content.replace(old, new, 1), encoding='utf-8')
