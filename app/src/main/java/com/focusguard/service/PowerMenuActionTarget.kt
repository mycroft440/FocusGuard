package com.focusguard.service

internal enum class ControlKind { LABEL, ANCESTOR, NEARBY, NONE }

internal data class ControlChoice(val kind: ControlKind, val index: Int = -1)

internal data class TargetPoint(val x: Int, val y: Int)

/** Área de um nó do menu nativo na tela; vazia quando ele não está visível. */
internal data class TargetBox(
    val left: Int,
    val top: Int,
    val right: Int,
    val bottom: Int,
    val clickable: Boolean,
    /** O nó tem texto próprio que não é desta ação (Cancelar, Modo avião, um título). */
    val foreignLabel: Boolean = false
) {
    val width: Int get() = right - left
    val height: Int get() = bottom - top
    val isEmpty: Boolean get() = width <= 0 || height <= 0
    val centerX: Int get() = left + width / 2
    val centerY: Int get() = top + height / 2

    fun contains(point: TargetPoint): Boolean =
        !isEmpty && point.x in left until right && point.y in top until bottom

    /** Zero dentro da área; senão o quadrado da distância até a borda mais perto. */
    fun distanceSquaredTo(point: TargetPoint): Long {
        val dx = maxOf(left - point.x, 0, point.x - (right - 1)).toLong()
        val dy = maxOf(top - point.y, 0, point.y - (bottom - 1)).toLong()
        return dx * dx + dy * dy
    }
}

/**
 * Onde acionar uma ação do menu de energia nativo, sem depender do Android para os
 * testes.
 *
 * Os menus de energia fecham quando o toque ou o clique cai no fundo ou no contêiner
 * dos botões, e há menus em que o texto da ação fica fora do botão (ícone em cima,
 * texto embaixo). Acionar o primeiro ancestral "clicável" do texto acertava esse
 * contêiner: o menu fechava e nada acontecia.
 */
internal object PowerMenuActionTargetPolicy {
    /** O botão de uma ação nunca passa de um terço da tela; o fundo do menu, sim. */
    const val MAX_ACTION_TARGET_SCREEN_FRACTION = 3

    /** Um botão de ação ocupa uma parte pequena da tela; o fundo do menu, não. */
    fun isPlausibleActionTarget(
        width: Int,
        height: Int,
        screenWidth: Int,
        screenHeight: Int
    ): Boolean {
        if (screenWidth <= 0 || screenHeight <= 0) return true
        val area = width.coerceAtLeast(0).toLong() * height.coerceAtLeast(0)
        return area * MAX_ACTION_TARGET_SCREEN_FRACTION <= screenWidth.toLong() * screenHeight
    }

    /**
     * Escolhe o controle que executa a ação do rótulo [label]:
     * 1. o próprio rótulo, quando ele é o botão;
     * 2. o botão em volta dele (a linha ou o cartão da ação);
     * 3. o botão mais perto dele entre os vizinhos [nearby], quando o texto fica fora
     *    do botão, desde que esse botão esteja mais perto deste rótulo do que de
     *    qualquer outra ação e não tenha nem contenha texto de outra coisa
     *    ([foreignTextCenters]): um "Cancelar" ao lado nunca é o botão da ação.
     * Nunca um nó que contenha outra ação: é o contêiner ou o fundo do menu, que fecha
     * o menu quando acionado. A subida pelos ancestrais [ancestors] (do mais perto ao
     * mais longe) para no primeiro deles. Sem nenhum controle assim, [ControlKind.NONE].
     */
    fun chooseActionControl(
        label: TargetBox,
        ancestors: List<TargetBox>,
        nearby: List<TargetBox>,
        otherActionCenters: List<TargetPoint>,
        screenWidth: Int,
        screenHeight: Int,
        foreignTextCenters: List<TargetPoint> = emptyList()
    ): ControlChoice {
        fun usable(box: TargetBox): Boolean = box.clickable && !box.isEmpty &&
            otherActionCenters.none(box::contains) &&
            isPlausibleActionTarget(box.width, box.height, screenWidth, screenHeight)

        if (usable(label)) return ControlChoice(ControlKind.LABEL)
        for ((index, ancestor) in ancestors.withIndex()) {
            if (otherActionCenters.any(ancestor::contains)) break
            if (usable(ancestor)) return ControlChoice(ControlKind.ANCESTOR, index)
        }
        if (label.isEmpty) return ControlChoice(ControlKind.NONE)
        val labelCenter = TargetPoint(label.centerX, label.centerY)
        val nearest = nearby.withIndex()
            .filter { (_, box) ->
                usable(box) && !box.foreignLabel && foreignTextCenters.none(box::contains)
            }
            .filter { (_, box) ->
                val toLabel = box.distanceSquaredTo(labelCenter)
                otherActionCenters.all { toLabel < box.distanceSquaredTo(it) }
            }
            .minByOrNull { (_, box) -> box.distanceSquaredTo(labelCenter) }
            ?: return ControlChoice(ControlKind.NONE)
        return ControlChoice(ControlKind.NEARBY, nearest.index)
    }

    /** Primeiro ancestral que também contém outra ação, ou -1. */
    fun firstSharedContainerIndex(
        ancestors: List<TargetBox>,
        otherActionCenters: List<TargetPoint>
    ): Int = ancestors.indexOfFirst { ancestor ->
        otherActionCenters.any(ancestor::contains)
    }
}
