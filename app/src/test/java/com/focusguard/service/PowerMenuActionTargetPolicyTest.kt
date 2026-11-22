package com.focusguard.service

import com.google.common.truth.Truth.assertThat
import org.junit.Test

/**
 * O toque ou o clique encaminhado ao menu de energia nativo precisa cair no botão da
 * ação. No fundo ou no contêiner dos botões ele fecha o menu sem fazer nada.
 */
class PowerMenuActionTargetPolicyTest {
    private val screenWidth = 1_080
    private val screenHeight = 2_400

    @Test
    fun `a clickable label is the control itself`() {
        val label = box(100, 1_000, 500, 1_100, clickable = true)

        assertThat(choose(label)).isEqualTo(ControlChoice(ControlKind.LABEL))
    }

    @Test
    fun `a label inside its action row presses the row`() {
        val label = box(200, 1_020, 400, 1_080)
        val row = box(80, 1_000, 1_000, 1_100, clickable = true)
        val card = box(40, 800, 1_040, 1_600, clickable = true)

        val choice = choose(
            label,
            ancestors = listOf(row, card),
            otherCenters = listOf(TargetPoint(300, 1_250))
        )

        assertThat(choice).isEqualTo(ControlChoice(ControlKind.ANCESTOR, 0))
    }

    @Test
    fun `the clickable card that also holds another action is never pressed`() {
        // O texto não é clicável e o primeiro ancestral clicável é o cartão do menu,
        // que guarda também "Reiniciar": clicar nele fecha o menu.
        val label = box(200, 1_020, 400, 1_080)
        val wrapper = box(150, 1_000, 450, 1_100)
        val card = box(100, 900, 980, 1_400, clickable = true)

        val choice = choose(
            label,
            ancestors = listOf(wrapper, card),
            otherCenters = listOf(TargetPoint(700, 1_050))
        )

        assertThat(choice.kind).isEqualTo(ControlKind.NONE)
    }

    @Test
    fun `a label below its own icon button presses that button`() {
        // Ícone redondo clicável em cima, texto embaixo, lado a lado com Reiniciar.
        val powerOffLabel = box(140, 1_210, 400, 1_260)
        val powerOffButton = box(190, 1_000, 350, 1_160, clickable = true)
        val restartButton = box(690, 1_000, 850, 1_160, clickable = true)
        val restartLabelCenter = TargetPoint(770, 1_235)
        val row = box(0, 950, 1_080, 1_300, clickable = true) // fundo: fecha o menu

        val choice = choose(
            powerOffLabel,
            ancestors = listOf(row),
            nearby = listOf(row, restartButton, powerOffButton),
            otherCenters = listOf(restartLabelCenter)
        )

        assertThat(choice).isEqualTo(ControlChoice(ControlKind.NEARBY, 2))
    }

    @Test
    fun `a nearby button closer to another action is not taken`() {
        val label = box(140, 1_210, 400, 1_260)
        val restartButton = box(690, 1_000, 850, 1_160, clickable = true)

        val choice = choose(
            label,
            nearby = listOf(restartButton),
            otherCenters = listOf(TargetPoint(770, 1_235))
        )

        assertThat(choice.kind).isEqualTo(ControlKind.NONE)
    }

    @Test
    fun `a cancel button beside the label is never the control`() {
        // Confirmação com o título "Desligar" (não clicável) e um "Cancelar" ao lado.
        val title = box(140, 1_000, 600, 1_060)
        val cancel = TargetBox(140, 1_100, 500, 1_200, clickable = true, foreignLabel = true)

        assertThat(choose(title, nearby = listOf(cancel)).kind).isEqualTo(ControlKind.NONE)
    }

    @Test
    fun `a clickable group around a foreign text is never the control`() {
        val label = box(140, 1_210, 400, 1_260)
        val airplaneGroup = box(140, 1_000, 500, 1_190, clickable = true)
        val airplaneText = TargetPoint(320, 1_150)

        val choice = choose(
            label,
            nearby = listOf(airplaneGroup),
            foreignTexts = listOf(airplaneText)
        )

        assertThat(choice.kind).isEqualTo(ControlKind.NONE)
    }

    @Test
    fun `a full screen clickable background is never the control`() {
        val label = box(200, 1_020, 400, 1_080)
        val background = box(0, 0, screenWidth, screenHeight, clickable = true)

        val choice = choose(label, ancestors = listOf(background), nearby = listOf(background))

        assertThat(choice.kind).isEqualTo(ControlKind.NONE)
    }

    @Test
    fun `a label that is not on screen finds no neighbour`() {
        val hidden = TargetBox(0, 0, 0, 0, clickable = false)
        val button = box(190, 1_000, 350, 1_160, clickable = true)

        assertThat(choose(hidden, nearby = listOf(button)).kind).isEqualTo(ControlKind.NONE)
    }

    @Test
    fun `the shared container is the first ancestor that holds another action`() {
        val wrapper = box(150, 1_000, 450, 1_100)
        val row = box(0, 950, 1_080, 1_300)
        val other = listOf(TargetPoint(770, 1_235))

        assertThat(PowerMenuActionTargetPolicy.firstSharedContainerIndex(listOf(wrapper, row), other))
            .isEqualTo(1)
        assertThat(PowerMenuActionTargetPolicy.firstSharedContainerIndex(listOf(wrapper), other))
            .isEqualTo(-1)
    }

    private fun choose(
        label: TargetBox,
        ancestors: List<TargetBox> = emptyList(),
        nearby: List<TargetBox> = emptyList(),
        otherCenters: List<TargetPoint> = emptyList(),
        foreignTexts: List<TargetPoint> = emptyList()
    ): ControlChoice = PowerMenuActionTargetPolicy.chooseActionControl(
        label = label,
        ancestors = ancestors,
        nearby = nearby,
        otherActionCenters = otherCenters,
        screenWidth = screenWidth,
        screenHeight = screenHeight,
        foreignTextCenters = foreignTexts
    )

    private fun box(left: Int, top: Int, right: Int, bottom: Int, clickable: Boolean = false) =
        TargetBox(left, top, right, bottom, clickable)
}
