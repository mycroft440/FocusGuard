package com.focusguard.sitesblocker

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.provider.Settings
import android.widget.Toast
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.focusguard.ui.compose.layout.FocusGuardScreenScaffold
import com.focusguard.ui.compose.layout.FocusGuardScrollableContent
import com.focusguard.ui.compose.layout.FocusGuardSectionHeader
import com.focusguard.utils.PermissionUtils

/**
 * Tela do bloqueio de sites (antigo app Bloquear Sites): serviço de acessibilidade, uso da
 * bateria em segundo plano, bloqueio de pornografia, lista de domínios e navegadores instalados.
 */
@Composable
fun SitesBlockerScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val activity = remember(context) { context.findActivity() }
    val store = remember { BlockedSitesStore(context) }

    // Tudo é relido a cada retorno à tela (onResume), como na tela original.
    var resumeTick by remember { mutableIntStateOf(0) }
    // O pedido de bateria aparece sozinho uma vez a cada abertura da tela, enquanto não for aceito.
    var batteryPrompted by rememberSaveable { mutableStateOf(false) }
    var awaitingBatteryAnswer by remember { mutableStateOf(false) }

    fun requestBattery() {
        val host = activity ?: return
        awaitingBatteryAnswer = true
        BackgroundAccess.requestIgnoreBatteryOptimizations(host)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event != Lifecycle.Event.ON_RESUME) return@LifecycleEventObserver
            resumeTick++
            val unrestricted = BackgroundAccess.isIgnoringBatteryOptimizations(context)
            if (awaitingBatteryAnswer) {
                awaitingBatteryAnswer = false
                if (unrestricted) {
                    Toast.makeText(
                        context,
                        if (BackgroundAccess.hasManufacturerRestrictions()) {
                            "Bateria liberada. Na Xiaomi, libere também o início automático."
                        } else {
                            "Pronto! O bloqueio funciona na hora, mesmo com o app fechado."
                        },
                        Toast.LENGTH_LONG
                    ).show()
                }
            } else if (!unrestricted && !batteryPrompted) {
                batteryPrompted = true
                requestBattery()
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    val serviceEnabled = remember(resumeTick) {
        PermissionUtils.isAccessibilityServiceEnabled(context)
    }
    val unrestricted = remember(resumeTick) {
        BackgroundAccess.isIgnoringBatteryOptimizations(context)
    }
    var sitesVersion by remember { mutableIntStateOf(0) }
    val domains = remember(resumeTick, sitesVersion) { store.sortedList }
    val browsers = remember(resumeTick) { installedBrowserLines(context) }

    var adultEnabled by remember { mutableStateOf(store.isAdultFilterEnabled) }
    var siteInput by rememberSaveable { mutableStateOf("") }
    var showDisclosure by remember { mutableStateOf(false) }
    var domainToRemove by remember { mutableStateOf<String?>(null) }

    fun addSite() {
        val normalized = store.add(siteInput)
        if (normalized == null) {
            Toast.makeText(context, "Digite um domínio ou URL válido.", Toast.LENGTH_SHORT).show()
            return
        }
        siteInput = ""
        sitesVersion++
        Toast.makeText(context, "$normalized foi bloqueado.", Toast.LENGTH_SHORT).show()
    }

    FocusGuardScreenScaffold(title = "Bloquear sites", onBack = onBack) { paddingValues ->
        FocusGuardScrollableContent(paddingValues = paddingValues) {
            HintText(
                "Adicione um domínio. O bloqueio vale também para os subdomínios.",
                size = 15
            )
            Spacer(Modifier.height(16.dp))

            StatusBox(
                text = if (serviceEnabled) {
                    "Serviço ativo — bloqueio ligado"
                } else {
                    "Serviço inativo — ative para bloquear"
                },
                ok = serviceEnabled
            )
            Button(
                onClick = { showDisclosure = true },
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 10.dp, bottom = 18.dp)
            ) { Text("Ativar acessibilidade") }

            // Funcionamento em segundo plano: com a bateria otimizada, o sistema atrasa o bloqueio.
            StatusBox(
                text = if (unrestricted) {
                    "✅ Bateria sem restrição — bloqueio em segundo plano liberado"
                } else {
                    "⚠ Bateria otimizada — toque aqui para liberar"
                },
                ok = unrestricted,
                onClick = { if (!unrestricted) requestBattery() }
            )
            if (!unrestricted) {
                HintText(
                    "Com a bateria otimizada, o sistema atrasa o bloqueio quando o app" +
                        " não está aberto. Toque em \"Liberar uso da bateria\" e depois em \"Permitir\".",
                    modifier = Modifier.padding(top = 6.dp)
                )
                Button(
                    onClick = { requestBattery() },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(top = 6.dp)
                ) { Text("Liberar uso da bateria") }
            }
            if (BackgroundAccess.hasManufacturerRestrictions() && activity != null) {
                HintText(
                    "Na Xiaomi, ative também o início automático e escolha" +
                        " \"Sem restrições\" na economia de bateria do app.",
                    modifier = Modifier.padding(top = 6.dp)
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { BackgroundAccess.openAutoStartSettings(activity) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Início automático") }
                    OutlinedButton(
                        onClick = { BackgroundAccess.openManufacturerBatterySettings(activity) },
                        modifier = Modifier.weight(1f)
                    ) { Text("Economia de bateria") }
                }
            }
            Spacer(Modifier.height(18.dp))

            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    "Bloquear pornografia",
                    fontSize = 17.sp,
                    color = MaterialTheme.colorScheme.onBackground,
                    modifier = Modifier.weight(1f)
                )
                Switch(
                    checked = adultEnabled,
                    onCheckedChange = { checked ->
                        store.setAdultFilterEnabled(checked)
                        adultEnabled = checked
                        Toast.makeText(
                            context,
                            if (checked) {
                                "Bloqueio de pornografia ligado."
                            } else {
                                "Bloqueio de pornografia desligado."
                            },
                            Toast.LENGTH_SHORT
                        ).show()
                    }
                )
            }
            HintText(
                "Bloqueia sites pornográficos e buscas explícitas, inclusive no Google Imagens e" +
                    " Vídeos, pelo endereço e pelo texto da página. As imagens em si não são analisadas.",
                modifier = Modifier.padding(bottom = 16.dp)
            )

            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = siteInput,
                    onValueChange = { siteInput = it },
                    placeholder = { Text("exemplo.com") },
                    singleLine = true,
                    keyboardOptions = KeyboardOptions(
                        keyboardType = KeyboardType.Uri,
                        imeAction = ImeAction.Done
                    ),
                    keyboardActions = KeyboardActions(onDone = { addSite() }),
                    modifier = Modifier.weight(1f)
                )
                Button(
                    onClick = { addSite() },
                    modifier = Modifier.padding(start = 8.dp)
                ) { Text("Adicionar") }
            }

            Spacer(Modifier.height(22.dp))
            FocusGuardSectionHeader("Sites bloqueados")
            Spacer(Modifier.height(8.dp))
            if (domains.isEmpty()) {
                HintText("Nenhum site adicionado.", modifier = Modifier.padding(vertical = 12.dp))
            } else {
                domains.forEach { domain ->
                    Text(
                        domain,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onBackground,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable { domainToRemove = domain }
                            .padding(vertical = 12.dp)
                    )
                }
            }

            Spacer(Modifier.height(18.dp))
            FocusGuardSectionHeader("Navegadores instalados")
            HintText(
                "Só os suportados ficam liberados. Enquanto houver sites na lista ou o bloqueio" +
                    " de pornografia estiver ligado, os não suportados são fechados ao abrir.",
                modifier = Modifier.padding(top = 2.dp, bottom = 8.dp)
            )
            Text(
                browsers,
                fontSize = 14.sp,
                lineHeight = 20.sp,
                color = MaterialTheme.colorScheme.onBackground
            )

            HintText(
                "A leitura da barra de endereço acontece localmente no aparelho. Nenhuma URL," +
                    " histórico ou lista de sites é enviada para fora dele.",
                modifier = Modifier.padding(top = 12.dp, bottom = 24.dp)
            )
        }
    }

    if (showDisclosure) {
        AlertDialog(
            onDismissRequest = { showDisclosure = false },
            title = { Text("Uso do serviço de acessibilidade") },
            text = {
                Text(
                    "Para bloquear os sites que você escolher, o app precisa usar o serviço de acessibilidade para ler o texto visível da barra de endereço dos navegadores e identificar o domínio aberto.\n\n" +
                        "A URL é comparada somente no aparelho com a sua lista de bloqueio. O app não envia URLs, histórico ou a lista de sites a terceiros e não altera configurações sem sua ação.\n\n" +
                        "Quando um domínio bloqueado é detectado, o app cobre a tela e leva o navegador para o Google: toca na barra de endereço, digita google.com e confirma, trocando o site da aba atual. Se não conseguir, abre o Google em uma aba nova.\n\n" +
                        "Enquanto houver sites na lista ou o bloqueio de pornografia estiver ligado, só os navegadores suportados ficam liberados: os demais são fechados ao abrir, voltando para a tela inicial.\n\n" +
                        "Com o bloqueio de pornografia ligado, o app também lê, somente no aparelho, o texto das páginas abertas (títulos, resultados de busca e o que foi pesquisado) para identificar conteúdo adulto. Nada é enviado para fora do aparelho."
                )
            },
            dismissButton = {
                TextButton(onClick = { showDisclosure = false }) { Text("Cancelar") }
            },
            confirmButton = {
                TextButton(onClick = {
                    showDisclosure = false
                    try {
                        context.startActivity(
                            Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
                                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                        )
                    } catch (e: RuntimeException) {
                        Toast.makeText(
                            context,
                            "Não foi possível abrir as configurações de acessibilidade.",
                            Toast.LENGTH_LONG
                        ).show()
                    }
                }) { Text("Concordo") }
            }
        )
    }

    domainToRemove?.let { domain ->
        AlertDialog(
            onDismissRequest = { domainToRemove = null },
            title = { Text("Remover bloqueio") },
            text = { Text("Remover $domain da lista?") },
            dismissButton = {
                TextButton(onClick = { domainToRemove = null }) { Text("Cancelar") }
            },
            confirmButton = {
                TextButton(onClick = {
                    store.remove(domain)
                    domainToRemove = null
                    sitesVersion++
                }) { Text("Remover") }
            }
        )
    }
}

@Composable
private fun HintText(text: String, modifier: Modifier = Modifier, size: Int = 12) {
    Text(
        text,
        fontSize = size.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier
    )
}

@Composable
private fun StatusBox(text: String, ok: Boolean, onClick: (() -> Unit)? = null) {
    val textColor = if (ok) Color(0xFF00693E) else Color(0xFFAA4600)
    val background = if (ok) Color(0xFFE2F4EA) else Color(0xFFFFEFDC)
    Column(
        Modifier
            .fillMaxWidth()
            .background(background, RoundedCornerShape(8.dp))
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 12.dp, vertical = 10.dp)
    ) {
        Text(text, color = textColor, fontSize = 15.sp)
    }
}

/**
 * Navegadores instalados: ✅ suportado (com a família), ⛔ não suportado ou com a barra não lida
 * nesta versão (bloqueado), ❓ em teste.
 */
private fun installedBrowserLines(context: Context): String {
    IdentifiedBrowsers.load(context)
    val detector = BrowserDetector(context)
    val verified = VerifiedBrowsers(context)
    val supported = mutableListOf<String>()
    val blocked = mutableListOf<String>()
    val pending = mutableListOf<String>()

    for (packageName in detector.installedBrowsers()) {
        val label = detector.labelOf(packageName)
        val profile = BrowserProfiles.forPackage(packageName)
        when {
            profile != null && verified.hasFailed(packageName) -> blocked +=
                "⛔ $label — barra de endereço não lida nesta versão: bloqueado"
            profile != null -> supported += "✅ $label — suportado (${profile.family})"
            BrowserProfiles.isKnownUnsupported(packageName) ||
                IdentifiedBrowsers.isRejected(context, packageName) -> blocked +=
                "⛔ $label — não suportado: bloqueado"
            else -> pending += "❓ $label — em teste: é verificado ao abrir uma" +
                " página e bloqueado se não for compatível"
        }
    }

    val sections = listOf(supported, blocked, pending)
        .filter { it.isNotEmpty() }
        .map { lines -> lines.sortedWith(String.CASE_INSENSITIVE_ORDER).joinToString("\n") }
    return if (sections.isEmpty()) "Nenhum navegador encontrado." else sections.joinToString("\n\n")
}

private fun Context.findActivity(): Activity? {
    var current: Context? = this
    while (current is ContextWrapper) {
        if (current is Activity) return current
        current = current.baseContext
    }
    return null
}
