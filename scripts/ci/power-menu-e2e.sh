#!/usr/bin/env bash
# Teste de ponta a ponta do menu de energia protegido, num emulador com o System UI
# do Android.
#
# 1. Instala o app, liga o serviço de acessibilidade e cria um bloqueio por tempo
#    ativo (é com ele que a proteção do menu fica de prontidão).
# 2. Abre a cortina e a tela de bloqueio com notificações que citam as ações
#    ("Restart now", "Turn off", "Reiniciar", "Desligar"): a proteção não pode abrir
#    sozinha. Roda com a versão anterior da detecção (controle) e com a atual.
# 3. Abre o menu de energia segurando o botão de ligar: a proteção precisa cobrir o
#    menu nativo.
# 4. Toca em Emergência, Reiniciar e Desligar na proteção e confere se o Android
#    executa cada uma (discador de emergência, reinício e desligamento).
#
# Resultados, capturas de tela e logs ficam em power-menu-e2e/.
set -u

OUT="power-menu-e2e"
mkdir -p "$OUT"
RESULTS="$OUT/results.txt"
: > "$RESULTS"
PKG="com.focusguard.v2.debug"
SVC="$PKG/com.focusguard.service.BlockingAccessibilityService"
DB="/data/data/$PKG/databases/focusguard_database"

log() { echo "$*" | tee -a "$RESULTS"; }
shot() { adb exec-out screencap -p > "$OUT/$1.png" 2>/dev/null || true; }
dump_windows() { adb shell dumpsys window windows > "$OUT/$1-windows.txt" 2>/dev/null || true; }

# VISIVEL, OCULTA ou AUSENTE: estado da janela da proteção do menu de energia.
overlay_state() {
    adb shell dumpsys window windows 2>/dev/null | tr -d '\r' | python3 -c '
import sys
text = sys.stdin.read()
marker = "FocusGuardProtectedPowerMenu}"
index = text.find(marker)
if index < 0:
    print("AUSENTE")
    sys.exit()
block = text[index:index + 4000]
attrs = next((line for line in block.splitlines() if "mAttrs=" in line), "")
hidden = "alpha=0.0" in attrs or " NOT_TOUCHABLE" in attrs
print("OCULTA" if hidden else "VISIVEL")
'
}

# Registros do menu de energia (do app e do sistema) desde a última chamada.
power_logs() {
    log "  --- registros ($1) ---"
    adb logcat -d 2>/dev/null | tr -d '\r' \
        | grep -E "PowerMenu|ShutdownThread|GlobalActions|RestartAction|ShutdownAction|EmergencyDialer" \
        | tail -n 40 | sed 's/^/    /' | tee -a "$RESULTS"
    adb logcat -c >/dev/null 2>&1 || true
}

# Árvore do menu de energia nativo, como a acessibilidade a vê. O uiautomator
# suspende os outros serviços de acessibilidade enquanto lê; o do app volta depois.
dump_native_menu() {
    log "[atual] Árvore do menu de energia nativo"
    open_power_menu
    adb shell uiautomator dump /sdcard/native-menu.xml >/dev/null 2>&1
    adb pull /sdcard/native-menu.xml "$OUT/native-menu.xml" >/dev/null 2>&1
    python3 - "$OUT/native-menu.xml" <<'PY' | tee -a "$RESULTS"
import re, sys
try:
    xml = open(sys.argv[1], encoding="utf-8").read()
except OSError:
    print("    (sem árvore)")
    sys.exit()
for node in re.findall(r"<node [^>]*>", xml):
    attrs = dict(re.findall(r'(\w[\w-]*)="([^"]*)"', node))
    if attrs.get("package") != "com.android.systemui":
        continue
    text = attrs.get("text") or attrs.get("content-desc") or ""
    if not text and attrs.get("clickable") != "true":
        continue
    print("    {} '{}' {} clicável={} id={}".format(
        attrs.get("class", "").split(".")[-1], text, attrs.get("bounds"),
        attrs.get("clickable"), attrs.get("resource-id", "").split("/")[-1]))
PY
    adb shell input keyevent KEYCODE_BACK
    sleep 10
    go_home
    log "  proteção de prontidão de novo: $(overlay_state)"
}

focused_window() {
    adb shell dumpsys window 2>/dev/null | tr -d '\r' | grep -m1 "mCurrentFocus" | sed 's/^ *//'
}

wait_boot() {
    adb wait-for-device
    for _ in $(seq 1 150); do
        if [ "$(adb shell getprop sys.boot_completed 2>/dev/null | tr -d '\r')" = "1" ]; then
            return 0
        fi
        sleep 2
    done
    return 1
}

become_root() {
    adb root >/dev/null 2>&1 || true
    sleep 3
    wait_boot
}

# Toque no centro de um cartão da proteção. O cartão é achado pela posição no
# layout (dp a partir do fim da tela): o menu da proteção fica preso embaixo.
#   Cancelar 80 · Informações médicas 147 · Emergência 218 · Reiniciar 326 · Desligar 397
tap_card() {
    local from_bottom_dp="$1"
    local size density width height
    size="$(adb shell wm size | tr -d '\r' | awk '/Physical size/ {print $3}')"
    density="$(adb shell wm density | tr -d '\r' | awk '/Physical density/ {print $3}')"
    width="${size%x*}"
    height="${size#*x}"
    local x=$(( width / 2 ))
    local y
    y="$(python3 -c "print(round($height - $from_bottom_dp * $density / 160))")"
    log "  toque em ($x, $y) para o cartão a ${from_bottom_dp} dp do fim da tela"
    adb shell input tap "$x" "$y"
}

open_power_menu() {
    adb shell input keyevent --longpress KEYCODE_POWER
    sleep 3
}

go_home() {
    adb shell input keyevent KEYCODE_HOME
    sleep 2
}

install_and_arm() {
    local apk="$1"
    log "Instalando $apk"
    adb install -r -g "$apk" >> "$RESULTS" 2>&1
    adb shell settings put secure enabled_accessibility_services "$SVC"
    adb shell settings put secure accessibility_enabled 1
    sleep 8
    if ! adb shell "test -f $DB"; then
        log "  banco ainda não existe; abrindo o app uma vez"
        adb shell monkey -p "$PKG" -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1
        sleep 8
        go_home
    fi
    local sessions
    sessions="$(adb shell "sqlite3 $DB 'SELECT count(*) FROM block_sessions WHERE isActive = 1;'" | tr -d '\r')"
    if [ "${sessions:-0}" = "0" ]; then
        local now_ms
        now_ms="$(( $(adb shell date +%s | tr -d '\r') * 1000 ))"
        adb shell "sqlite3 $DB \"INSERT INTO block_sessions (startTime, endTime, durationDays, isActive, blockedAppsCount, blockedWebsitesCount, createdAt, isRecurring, recurringStartHour, recurringStartMinute, recurringEndHour, recurringEndMinute, recurringDaysOfWeek, recurringDurationMonths, sessionType, isFixed24h, isBlockingEnabled, cancelableUntil) VALUES ($(( now_ms - 60000 )), $(( now_ms + 4 * 3600000 )), 0, 1, 1, 0, $now_ms, 0, 0, 0, 0, 0, '', 1, 'TIME', 1, 1, NULL); INSERT INTO session_app_cross_ref (sessionId, packageName) SELECT max(id), 'com.example.e2e.blocked' FROM block_sessions;\""
        local owner
        owner="$(adb shell stat -c %u:%g "/data/data/$PKG" | tr -d '\r')"
        adb shell "chown $owner /data/data/$PKG/databases/*; restorecon -R /data/data/$PKG/databases" || true
        log "  bloqueio por tempo criado (4 h)"
    fi
    # Eventos de janela fazem o serviço reler o banco (a cada 5 s no máximo).
    go_home
    sleep 6
    go_home
    sleep 3
    log "  proteção do menu de prontidão: $(overlay_state)"
}

post_notifications() {
    adb shell cmd notification post -t "'System update'" e2e_update "'Restart now'" >> "$RESULTS" 2>&1
    adb shell cmd notification post -t Hotspot e2e_hotspot "'Turn off'" >> "$RESULTS" 2>&1
    adb shell cmd notification post -t "'Atualização'" e2e_update_pt Reiniciar >> "$RESULTS" 2>&1
    adb shell cmd notification post -t "'Ponto de acesso'" e2e_hotspot_pt Desligar >> "$RESULTS" 2>&1
    sleep 2
    log "  notificações de teste na lista: $(adb shell dumpsys notification --noredact | grep -c 'pkg=com.android.shell' | tr -d '\r')"
}

# A proteção não pode abrir sem o menu de energia na tela.
false_positive_round() {
    local label="$1"
    log "[$label] Cortina de notificações com textos que citam as ações"
    post_notifications
    adb shell cmd statusbar expand-notifications
    sleep 3
    local in_shade_first in_shade_later
    in_shade_first="$(overlay_state)"
    # Fica aberta além de uma virada de minuto do relógio da barra de status.
    sleep 62
    in_shade_later="$(overlay_state)"
    shot "$label-cortina"
    dump_windows "$label-cortina"
    log "  proteção com a cortina aberta: logo=$in_shade_first, depois de 1 min=$in_shade_later"
    adb shell cmd statusbar collapse
    sleep 2

    log "[$label] Tela de bloqueio com as mesmas notificações"
    adb shell input keyevent KEYCODE_SLEEP
    sleep 3
    adb shell input keyevent KEYCODE_WAKEUP
    sleep 4
    local on_lock
    on_lock="$(overlay_state)"
    shot "$label-tela-de-bloqueio"
    log "  proteção na tela de bloqueio: $on_lock"
    adb shell wm dismiss-keyguard >/dev/null 2>&1 || adb shell input keyevent 82
    sleep 2
    go_home
    adb shell cmd notification cancel e2e_update >/dev/null 2>&1 || true

    if [ "$in_shade_first" = "VISIVEL" ] || [ "$in_shade_later" = "VISIVEL" ] || [ "$on_lock" = "VISIVEL" ]; then
        log "  RESULTADO [$label]: a proteção ABRIU SOZINHA"
    else
        log "  RESULTADO [$label]: a proteção não abriu sozinha"
    fi
}

shield_round() {
    log "[atual] Menu de energia aberto pelo botão de ligar"
    open_power_menu
    local state focus
    state="$(overlay_state)"
    focus="$(focused_window)"
    shot "atual-menu-de-energia"
    dump_windows "atual-menu-de-energia"
    log "  janela em foco: $focus"
    log "  proteção: $state"
    if [ "$state" = "VISIVEL" ]; then
        log "  RESULTADO: a proteção cobre o menu de energia"
    else
        log "  RESULTADO: FALHA, a proteção não apareceu sobre o menu"
    fi

    log "[atual] Cancelar na proteção"
    tap_card 80
    sleep 3
    log "  depois de Cancelar: proteção=$(overlay_state), foco=$(focused_window)"
    shot "atual-depois-de-cancelar"
    power_logs "Cancelar"
    go_home
    adb shell input keyevent KEYCODE_BACK
    sleep 2
}

emergency_round() {
    log "[atual] Emergência"
    open_power_menu
    log "  proteção antes do toque: $(overlay_state)"
    shot "atual-emergencia-antes-do-toque"
    tap_card 218
    sleep 1
    shot "atual-emergencia-logo-depois"
    sleep 4
    local focus
    focus="$(focused_window)"
    shot "atual-emergencia"
    log "  janela em foco depois do toque: $focus"
    log "  proteção depois do toque: $(overlay_state)"
    if echo "$focus" | grep -qi "emergency\|dialer\|safety"; then
        log "  RESULTADO: Emergência abriu o discador de emergência"
    else
        log "  RESULTADO: FALHA, Emergência não abriu o discador"
    fi
    power_logs "Emergência"
    go_home
    adb shell input keyevent KEYCODE_BACK
    go_home
}

restart_round() {
    log "[atual] Reiniciar"
    open_power_menu
    log "  proteção antes do toque: $(overlay_state)"
    shot "atual-reiniciar-antes-do-toque"
    local uptime_before
    uptime_before="$(adb shell cut -d. -f1 /proc/uptime | tr -d '\r')"
    tap_card 326
    sleep 1
    shot "atual-reiniciar-logo-depois"
    local restarted="nao"
    for _ in $(seq 1 30); do
        sleep 2
        local uptime_now
        uptime_now="$(adb shell cut -d. -f1 /proc/uptime 2>/dev/null | tr -d '\r')"
        if [ -z "$uptime_now" ] || [ "$uptime_now" -lt "$uptime_before" ] 2>/dev/null; then
            restarted="sim"
            break
        fi
    done
    if [ "$restarted" = "nao" ]; then
        shot "atual-reiniciar-falhou"
        log "  proteção depois do toque: $(overlay_state), foco: $(focused_window)"
        log "  RESULTADO: FALHA, o aparelho não reiniciou"
        power_logs "Reiniciar"
        go_home
        return
    fi
    log "  RESULTADO: o aparelho reiniciou"
    wait_boot || log "  o aparelho não terminou de ligar"
    become_root
    sleep 15
    adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
    go_home
    shot "atual-depois-de-reiniciar"
    log "  depois de religar: proteção=$(overlay_state)"
}

power_off_round() {
    log "[atual] Desligar"
    open_power_menu
    log "  proteção antes do toque: $(overlay_state)"
    shot "atual-desligar-antes-do-toque"
    tap_card 397
    sleep 1
    shot "atual-desligar-logo-depois"
    local off="nao"
    for _ in $(seq 1 30); do
        sleep 2
        if ! adb shell true >/dev/null 2>&1; then
            off="sim"
            break
        fi
    done
    if [ "$off" = "sim" ]; then
        log "  RESULTADO: o aparelho desligou"
    else
        shot "atual-desligar-falhou"
        log "  proteção depois do toque: $(overlay_state), foco: $(focused_window)"
        log "  RESULTADO: FALHA, o aparelho não desligou"
        power_logs "Desligar"
    fi
}

# Preparação do aparelho para o teste:
# - segurar o botão de ligar abre o menu de energia (o padrão do Google é o Assistente);
# - tela de bloqueio ligada (deslizar), para ela aparecer ao acender a tela;
# - o shell pode postar notificações (o Android 13+ pede a permissão).
prepare_device() {
    adb shell settings put global power_button_long_press 1
    adb shell locksettings set-disabled false >/dev/null 2>&1 || true
    adb shell pm grant com.android.shell android.permission.POST_NOTIFICATIONS >/dev/null 2>&1 || true
    adb shell wm dismiss-keyguard >/dev/null 2>&1 || true
}

become_root
prepare_device
log "Aparelho: $(adb shell getprop ro.product.model | tr -d '\r'), Android $(adb shell getprop ro.build.version.release | tr -d '\r')"
log "Botão de ligar segurado: $(adb shell settings get global power_button_long_press | tr -d '\r') (1 = menu de energia)"

install_and_arm "e2e-apks/old.apk"
false_positive_round "anterior"

install_and_arm "e2e-apks/new.apk"
false_positive_round "atual"
adb logcat -c >/dev/null 2>&1 || true
shield_round
dump_native_menu
emergency_round

adb logcat -d > "$OUT/logcat-antes-de-reiniciar.txt" 2>/dev/null || true
restart_round
adb logcat -d > "$OUT/logcat-depois-de-reiniciar.txt" 2>/dev/null || true
power_off_round

log "Fim"
exit 0
