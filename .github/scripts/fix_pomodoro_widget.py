from pathlib import Path


def once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise RuntimeError(f"{label}: expected 1 match, found {count}")
    return text.replace(old, new, 1)


service_path = Path("app/src/main/java/com/focusguard/service/PomodoroForegroundService.kt")
service = service_path.read_text()

if "import com.focusguard.widget.PomodoroWidgetProvider" not in service:
    service = once(
        service,
        "import com.focusguard.utils.FocusGuardLogger\n",
        "import com.focusguard.utils.FocusGuardLogger\nimport com.focusguard.widget.PomodoroWidgetProvider\n",
        "widget provider import",
    )

service = once(
    service,
    "    private var wakeLock: PowerManager.WakeLock? = null\n",
    "    private var wakeLock: PowerManager.WakeLock? = null\n"
    "    private var lastWidgetUpdateKey: String? = null\n",
    "widget update key",
)

service = once(
    service,
    '''                    if (!hasActivePlan()) {
                        cancelWatchdogAlarm(applicationContext)
                        stopSelf()
                        break
                    }

                    updateNotification()
''',
    '''                    if (!hasActivePlan()) {
                        PomodoroWidgetProvider.requestUpdate(applicationContext)
                        cancelWatchdogAlarm(applicationContext)
                        stopSelf()
                        break
                    }

                    updateNotification()
                    updateWidgetIfNeeded()
''',
    "watchdog widget refresh",
)

marker = "    private fun ensureLockActivityOnTop() {\n"
widget_method = '''    /**
     * The widget only renders whole remaining minutes. Refresh it when that visible
     * minute changes, when the phase/end-time changes, and once when the service
     * starts. This keeps the home-screen clock live without rebuilding a large
     * RemoteViews bitmap every two seconds with the notification loop.
     */
    private fun updateWidgetIfNeeded() {
        val runtime = PomodoroPlanStore(applicationContext).readRuntime()
            ?.takeIf { it.active }
        val updateKey = runtime?.let {
            val remaining = (it.intervalEndTime - System.currentTimeMillis())
                .coerceAtLeast(0L)
            val displayedMinutes = (remaining + 59_999L) / 60_000L
            "${it.phase}|${it.intervalEndTime}|$displayedMinutes"
        } ?: "inactive"

        if (updateKey == lastWidgetUpdateKey) return
        lastWidgetUpdateKey = updateKey
        PomodoroWidgetProvider.requestUpdate(applicationContext)
    }

'''
if "private fun updateWidgetIfNeeded()" not in service:
    index = service.find(marker)
    if index < 0:
        raise RuntimeError("widget method insertion marker missing")
    service = service[:index] + widget_method + service[index:]

service_path.write_text(service)


info_path = Path("app/src/main/res/xml/pomodoro_widget_info.xml")
info = info_path.read_text()
info = once(info, '    android:minHeight="290dp"\n', '    android:minHeight="310dp"\n', "widget minHeight")
info = once(info, '    android:minResizeHeight="270dp"\n', '    android:minResizeHeight="300dp"\n', "widget minResizeHeight")
info_path.write_text(info)


layout_path = Path("app/src/main/res/layout/widget_pomodoro.xml")
layout = layout_path.read_text()
for view_id in ("widget_pomodoro_configure", "widget_pomodoro_start"):
    anchor = f'            android:id="@+id/{view_id}"\n'
    replacement = anchor + '            android:clickable="true"\n            android:focusable="true"\n'
    if f'android:id="@+id/{view_id}"\n            android:clickable="true"' not in layout:
        layout = once(layout, anchor, replacement, f"{view_id} accessibility")
layout_path.write_text(layout)


renderer_path = Path("app/src/main/java/com/focusguard/widget/PomodoroWidgetClockRenderer.kt")
renderer = renderer_path.read_text()
renderer = once(
    renderer,
    '''        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        paint.textSize = 11f * unit
        paint.letterSpacingCompat(0.18f)
        paint.color = secondaryText
        val label = context.getString(R.string.fg_pomodoro_clock_minutes)
        canvas.drawText(label, center, numberBaseline + 19f * unit, paint)
        paint.letterSpacingCompat(0f)
''',
    '''        paint.typeface = Typeface.create(Typeface.MONOSPACE, Typeface.NORMAL)
        paint.textSize = 11f * unit
        paint.color = secondaryText
        val label = context.getString(R.string.fg_pomodoro_clock_minutes)
        drawTrackedText(
            canvas = canvas,
            text = label,
            centerX = center,
            baseline = numberBaseline + 19f * unit,
            paint = paint,
            trackingPx = 1.8f * unit
        )
''',
    "tracked minutes label",
)
renderer = once(
    renderer,
    '''    /** Paint letter spacing is available only on TextPaint; emulate it only where useful. */
    private fun Paint.letterSpacingCompat(@Suppress("UNUSED_PARAMETER") value: Float) = Unit
}''',
    '''    private fun drawTrackedText(
        canvas: Canvas,
        text: String,
        centerX: Float,
        baseline: Float,
        paint: Paint,
        trackingPx: Float
    ) {
        if (text.isEmpty()) return
        val widths = FloatArray(text.length)
        paint.getTextWidths(text, widths)
        val totalWidth = widths.sum() + trackingPx * (text.length - 1).coerceAtLeast(0)
        var x = centerX - totalWidth / 2f
        val previousAlign = paint.textAlign
        paint.textAlign = Paint.Align.LEFT
        text.forEachIndexed { index, char ->
            canvas.drawText(char.toString(), x, baseline, paint)
            x += widths[index] + trackingPx
        }
        paint.textAlign = previousAlign
    }
}''',
    "remove no-op letter spacing",
)
renderer_path.write_text(renderer)

plan_path = Path("docs/EXECUTION_PLAN_2026-08-27.md")
plan = plan_path.read_text() if plan_path.exists() else "# Plano de execução — 27/08/2026\n"
plan += '''\n## Widget Pomodoro — revisão final\n\n- Relógio do widget passa a atualizar quando o minuto visível muda e nas trocas de fase.\n- A atualização é acionada pelo serviço foreground já existente, sem depender do `updatePeriodMillis` de 30 minutos do Android.\n- Ao encerrar o plano, o widget é atualizado imediatamente para o estado inativo.\n- Altura mínima do widget foi alinhada ao conteúdo real para evitar corte em launchers redimensionáveis.\n- Controles do widget ficaram explicitamente focáveis/clicáveis.\n- O espaçamento de letras do rótulo do relógio agora é realmente desenhado, em vez de usar um no-op.\n'''
plan_path.write_text(plan)
