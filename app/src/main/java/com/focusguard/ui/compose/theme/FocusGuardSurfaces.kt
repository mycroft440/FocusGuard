package com.focusguard.ui.compose.theme

import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/**
 * Peças visuais compartilhadas do FocusGuard.
 *
 * Só acabamento: fundo, cartão, selo de ícone, cabeçalho de seção e etiqueta de
 * status. Nenhuma delas guarda estado de negócio nem decide navegação — cada
 * tela continua dona do que exibe e do que acontece ao tocar.
 */

/** Raio padrão dos cartões do app, alinhado ao `medium` de [FocusGuardShapes]. */
val FocusCardShape = RoundedCornerShape(18.dp)

private val GlowHeight = 300.dp
private const val PRESSED_SCALE = 0.975f

/**
 * Abaixo desta luminância um acento não serve como cor de texto sobre si mesmo.
 * Fica entre o vermelho de perigo (0.198) e o verde de sucesso (0.328), que são
 * os dois acentos mais escuros em uso.
 */
private const val DIM_ACCENT_LUMINANCE = 0.30f

/**
 * Degradê de superfície de cartão: o topo recebe um pouco mais de luz que a
 * base, o que sugere volume sem depender de sombra — sombra em fundo quase
 * preto vira mancha, não profundidade.
 */
@Composable
fun focusCardBrush(
    top: Color = SurfaceRaisedTop,
    bottom: Color = SurfaceRaisedBottom
): Brush = remember(top, bottom) { Brush.verticalGradient(listOf(top, bottom)) }

/**
 * Degradê de destaque construído sobre a cor de acento recebida.
 *
 * O acento entra de fato na composição (a cor do bloqueio, do aviso ou o ciano
 * principal); o degradê só varia a opacidade dele.
 */
@Composable
fun accentWashBrush(accent: Color): Brush = remember(accent) {
    Brush.linearGradient(
        listOf(accent.copy(alpha = 0.22f), accent.copy(alpha = 0.06f))
    )
}

/**
 * Fundo de tela com halo de luz ciano no topo.
 *
 * O halo dá um ponto de luz para a tela inteira respirar em vez de terminar em
 * um retângulo preto chapado, e reforça o azul ciano como cor da casa sem
 * pintar nada por cima do conteúdo.
 */
@Composable
fun FocusGuardAmbientBackground(
    modifier: Modifier = Modifier,
    enabled: Boolean = true,
    baseColor: Color = DarkBg,
    glowColor: Color = AccentCyanWash,
    content: @Composable BoxScope.() -> Unit
) {
    val glow = remember(glowColor) {
        Brush.verticalGradient(listOf(glowColor, Color.Transparent))
    }
    // [enabled] desligado é para a tela que aparece embutida em outra que já
    // pinta o halo. Dois halos empilhados não somam brilho: o de dentro traz a
    // própria base opaca, que apaga o de fora e recomeça no ponto mais claro,
    // deixando um corte horizontal no meio da tela.
    Box(modifier = if (enabled) modifier.background(baseColor) else modifier) {
        if (enabled) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(GlowHeight)
                    .align(Alignment.TopCenter)
                    .background(glow)
            )
        }
        content()
    }
}

/**
 * Cartão padrão do app.
 *
 * Substitui o par `Card` + `CardDefaults.cardColors(DarkCard)` + borda plana
 * que estava repetido em quase toda tela. Quando recebe [onClick] ele responde
 * ao toque encolhendo de leve; sem [onClick] é apenas um painel.
 */
@Composable
fun FocusCard(
    modifier: Modifier = Modifier,
    shape: RoundedCornerShape = FocusCardShape,
    brush: Brush = focusCardBrush(),
    border: BorderStroke = BorderStroke(1.dp, CardBorder),
    onClick: (() -> Unit)? = null,
    content: @Composable BoxScope.() -> Unit
) {
    // Painel sem clique não paga por animação de toque: numa lista longa seriam
    // dezenas de InteractionSource e camadas de gráfico criados à toa. Por isso
    // os dois casos são caminhos separados, e não um Modifier condicional.
    if (onClick == null) {
        Box(
            modifier = modifier
                .clip(shape)
                .background(brush)
                .border(border, shape),
            content = content
        )
        return
    }

    val interactionSource = remember { MutableInteractionSource() }
    val pressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed) PRESSED_SCALE else 1f,
        animationSpec = spring(
            dampingRatio = Spring.DampingRatioNoBouncy,
            stiffness = Spring.StiffnessMediumLow
        ),
        label = "FocusCardPress"
    )

    Box(
        modifier = modifier
            .graphicsLayer {
                scaleX = scale
                scaleY = scale
            }
            .clip(shape)
            .background(brush)
            .border(border, shape)
            // O clickable fica depois do fundo, e precisa continuar assim:
            // aplicado antes, o brilho do toque seria desenhado por baixo e o
            // cartão pareceria não responder. `role = Role.Button` é o que o
            // Card clicável do Material 3 colocava sozinho — sem ele o TalkBack
            // anuncia o cartão como texto e não avisa que dá para tocar.
            .clickable(
                interactionSource = interactionSource,
                indication = LocalIndication.current,
                role = Role.Button,
                onClick = onClick
            ),
        content = content
    )
}

/**
 * Selo que carrega o ícone de uma linha ou cartão.
 *
 * Antes cada tela desenhava sua própria caixinha de ícone com opacidade e raio
 * diferentes; aqui todas passam a ter o mesmo peso visual.
 */
@Composable
fun AccentIconBadge(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    accent: Color = AccentCyan,
    size: Dp = 44.dp,
    iconSize: Dp = 22.dp,
    shape: RoundedCornerShape = RoundedCornerShape(14.dp)
) {
    Box(
        modifier = modifier
            .size(size)
            .clip(shape)
            .background(accentWashBrush(accent))
            .border(1.dp, accent.copy(alpha = 0.22f), shape),
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = accent,
            modifier = Modifier.size(iconSize)
        )
    }
}

/**
 * Cabeçalho de seção com uma barrinha de acento à esquerda.
 *
 * O traço colorido dá início visível à seção: a lista embaixo passa a pertencer
 * a um título, em vez de flutuar depois de um texto solto.
 */
@Composable
fun FocusSectionLabel(
    text: String,
    modifier: Modifier = Modifier,
    accent: Color = AccentCyan
) {
    Row(
        modifier = modifier.padding(bottom = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(width = 3.dp, height = 14.dp)
                .clip(CircleShape)
                .background(accent)
        )
        Spacer(Modifier.width(9.dp))
        Text(
            text = text.uppercase(),
            color = accent,
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 0.9.sp
        )
    }
}

/**
 * Etiqueta arredondada de status (o "pronto", "ativo", a contagem de itens).
 *
 * O texto nem sempre pode ser o próprio acento. Acento claro (o ciano, o âmbar)
 * lido sobre ele mesmo a 12% dá contraste de sobra; acento escuro, como o
 * vermelho do jejum de dopamina, cai para 3.7:1 — abaixo do mínimo de leitura.
 * Nesses casos o texto vira branco e o acento fica onde continua legível: na
 * borda e no ponto.
 */
@Composable
fun StatusPill(
    text: String,
    modifier: Modifier = Modifier,
    accent: Color = AccentCyan,
    leadingDot: Boolean = false
) {
    val label = if (accent.luminance() < DIM_ACCENT_LUMINANCE) TextPrimary else accent
    Row(
        modifier = modifier
            .clip(CircleShape)
            .background(accent.copy(alpha = 0.12f))
            .border(1.dp, accent.copy(alpha = 0.28f), CircleShape)
            .padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        if (leadingDot) {
            Box(
                modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(accent)
            )
            Spacer(Modifier.width(7.dp))
        }
        Text(
            text = text,
            color = label,
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            letterSpacing = 0.5.sp
        )
    }
}
