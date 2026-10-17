package com.focusguard.ui.compose.theme

import android.view.WindowManager
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.displayCutout
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.union
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.focusguard.utils.findActivity

/**
 * Se a janela pediu `adjustResize` no manifesto: nela o conteúdo também precisa
 * sair de baixo do teclado.
 */
internal fun followsKeyboardResize(softInputMode: Int): Boolean =
    (softInputMode and WindowManager.LayoutParams.SOFT_INPUT_MASK_ADJUST) ==
        WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE

/**
 * Deixa o conteúdo fora das barras do sistema, sobre a mesma cor que o tema dá a
 * elas (dark_bg).
 *
 * O app mira o SDK 36: do Android 15 em diante a janela vai de ponta a ponta e o
 * Android ignora statusBarColor/navigationBarColor. Sem isto o topo de várias telas
 * ficava sob a barra de status, o banner e o "Prosseguir" sob a barra de navegação,
 * e uma tela dentro de outra (as abas da tela inicial) somava as margens duas vezes.
 * Os insets ficam consumidos aqui, então Scaffold, TopAppBar e os statusBarsPadding
 * de dentro não os aplicam de novo. Nessa janela o `adjustResize` também deixou de
 * encolher a tela, e o teclado cobria o campo de senha: nas telas que o pedem o
 * teclado entra na conta. Até o Android 14 a janela já desconta tudo isso e a
 * moldura não muda nada.
 */
@Composable
internal fun FocusGuardSystemBarsFrame(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val resizesForKeyboard = remember(context) {
        context.findActivity()?.window?.attributes?.softInputMode
            ?.let(::followsKeyboardResize) == true
    }
    val bars = WindowInsets.systemBars.union(WindowInsets.displayCutout)
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(DarkBg)
            .windowInsetsPadding(if (resizesForKeyboard) bars.union(WindowInsets.ime) else bars)
    ) {
        content()
    }
}
