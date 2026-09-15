from pathlib import Path


def replace_required(text: str, old: str, new: str, label: str, count: int = 1) -> str:
    actual = text.count(old)
    if actual < count:
        raise SystemExit(f"{label}: expected at least {count} occurrence(s), found {actual}")
    return text.replace(old, new, count)


# 1) Browser capability policy: DuckDuckGo's legacy omnibar must be clicked before
# editing, and its newer native input is only actionable when it identifies itself
# as an address/search field rather than a Duck.ai prompt.
policy_path = Path("app/src/main/java/com/focusguard/utils/BrowserUiCapabilityPolicy.kt")
policy = policy_path.read_text()

policy = replace_required(
    policy,
    """    private const val IME_ENTER_MIN_API = 30
""",
    """    private const val IME_ENTER_MIN_API = 30
    private const val DUCKDUCKGO_PACKAGE = \"com.duckduckgo.mobile.android\"
    private const val DUCKDUCKGO_NATIVE_INPUT_ENTRY = \"inputField\"
""",
    "DuckDuckGo policy constants",
)

policy = replace_required(
    policy,
    """        val text: String?,
        val contentDescription: String? = null,
        val actions: Set<NodeAction>
""",
    """        val text: String?,
        val contentDescription: String? = null,
        val actions: Set<NodeAction>,
        val hintText: String? = null
""",
    "Node hintText field",
)

policy = replace_required(
    policy,
    """        \"omnibarTextInput\",
        \"omnibox_text\",
        // Gecko/Fenix toolbars.
""",
    """        \"omnibarTextInput\",
        \"omnibox_text\",
        // DuckDuckGo's native input rollout. Authorization remains package- and
        // semantics-gated below because the id itself is intentionally generic.
        DUCKDUCKGO_NATIVE_INPUT_ENTRY,
        // Gecko/Fenix toolbars.
""",
    "DuckDuckGo native input query id",
)

policy = replace_required(
    policy,
    """        \"omnibarTextInput\",
        \"omnibox_text\",
        \"mozac_browser_toolbar_edit_url_view\"
""",
    """        \"omnibarTextInput\",
        \"omnibox_text\",
        DUCKDUCKGO_NATIVE_INPUT_ENTRY,
        \"mozac_browser_toolbar_edit_url_view\"
""",
    "DuckDuckGo editor rank",
)

policy = replace_required(
    policy,
    """        \"browser_toolbar_url_view\",
        \"address_bar\"
""",
    """        \"browser_toolbar_url_view\",
        \"address_bar\",
        // DuckDuckGo requires an explicit tap before its editor reliably accepts
        // replacement text. The native input id is authorized only after the
        // package-specific address-mode check in isActionableAddressBarNode().
        \"omnibarTextInput\",
        DUCKDUCKGO_NATIVE_INPUT_ENTRY
""",
    "DuckDuckGo clickable address fields",
)

policy = replace_required(
    policy,
    """        \"search or type web address\",
        \"pesquisar ou digitar endereço web\",
""",
    """        \"search or type web address\",
        \"pesquisar ou digitar endereço web\",
        \"search or enter address\",
        \"pesquisar ou inserir endereço\",
""",
    "DuckDuckGo address hints",
)

policy = replace_required(
    policy,
    """        val prefix = \"$expectedBrowserPackage:id/\"
        if (!viewIdResourceName.startsWith(prefix)) return false
        return viewIdResourceName.substring(prefix.length) in strongAddressBarEntryNames
""",
    """        val prefix = \"$expectedBrowserPackage:id/\"
        if (!viewIdResourceName.startsWith(prefix)) return false
        val entryName = viewIdResourceName.substring(prefix.length)
        if (entryName == DUCKDUCKGO_NATIVE_INPUT_ENTRY) {
            return expectedBrowserPackage == DUCKDUCKGO_PACKAGE
        }
        return entryName in strongAddressBarEntryNames
""",
    "package-gated strong DuckDuckGo input",
)

policy = replace_required(
    policy,
    """        if (isStrongAddressBarResource(node.viewIdResourceName, expectedBrowserPackage)) {
            return true
        }
""",
    """        if (isStrongAddressBarResource(node.viewIdResourceName, expectedBrowserPackage)) {
            return !isDuckDuckGoNativeInput(node) || isDuckDuckGoAddressInput(node)
        }
""",
    "read-only DuckDuckGo native input guard",
)

old_actionable = """    fun isActionableAddressBarNode(
        node: Node,
        expectedBrowserPackage: String,
        expectedWindowId: Int,
        httpsHandlerRecognized: Boolean = false
    ): Boolean = node.visible &&
        node.packageName == expectedBrowserPackage &&
        node.windowId == expectedWindowId &&
        (isStrongAddressBarResource(node.viewIdResourceName, expectedBrowserPackage) ||
            isSemanticActionableAddressBarNode(
                node = node,
                expectedBrowserPackage = expectedBrowserPackage,
                expectedWindowId = expectedWindowId,
                httpsHandlerRecognized = httpsHandlerRecognized
            ))
"""
new_actionable = """    fun isActionableAddressBarNode(
        node: Node,
        expectedBrowserPackage: String,
        expectedWindowId: Int,
        httpsHandlerRecognized: Boolean = false
    ): Boolean {
        if (!node.visible ||
            node.packageName != expectedBrowserPackage ||
            node.windowId != expectedWindowId
        ) return false

        if (isStrongAddressBarResource(node.viewIdResourceName, expectedBrowserPackage)) {
            return !isDuckDuckGoNativeInput(node) || isDuckDuckGoAddressInput(node)
        }
        return isSemanticActionableAddressBarNode(
            node = node,
            expectedBrowserPackage = expectedBrowserPackage,
            expectedWindowId = expectedWindowId,
            httpsHandlerRecognized = httpsHandlerRecognized
        )
    }
"""
policy = replace_required(policy, old_actionable, new_actionable, "actionable DuckDuckGo guard")

policy = replace_required(
    policy,
    """    private fun entryName(node: Node): String =
        node.viewIdResourceName.substringAfter(\":id/\", \"\")
""",
    """    private fun isDuckDuckGoNativeInput(node: Node): Boolean =
        node.packageName == DUCKDUCKGO_PACKAGE &&
            entryName(node) == DUCKDUCKGO_NATIVE_INPUT_ENTRY

    private fun isDuckDuckGoAddressInput(node: Node): Boolean {
        if (!isDuckDuckGoNativeInput(node) || !node.editable) return false
        if (node.uriInput) return true
        val labels = sequenceOf(node.contentDescription, node.hintText)
            .mapNotNull { it?.trim()?.lowercase(Locale.ROOT)?.takeIf(String::isNotEmpty) }
        return labels.any { value ->
            readOnlyAddressBarDescriptions.any { label ->
                value == label ||
                    value.startsWith(\"$label,\") ||
                    value.startsWith(\"$label.\") ||
                    value.startsWith(\"$label \" )
            }
        }
    }

    private fun entryName(node: Node): String =
        node.viewIdResourceName.substringAfter(\":id/\", \"\")
""",
    "DuckDuckGo native input helpers",
)

policy_path.write_text(policy)


# 2) Accessibility tree facts: include hint text so package-specific native address
# inputs can be distinguished from generic Duck.ai prompt fields.
blocker_path = Path("app/src/main/java/com/focusguard/utils/WebsiteBlocker.kt")
blocker = blocker_path.read_text()
blocker = replace_required(
    blocker,
    """        text = text?.toString(),
        contentDescription = contentDescription?.toString(),
        actions = actionList.mapNotNull { action ->
""",
    """        text = text?.toString(),
        contentDescription = contentDescription?.toString(),
        actions = actionList.mapNotNull { action ->
""",
    "WebsiteBlocker node prefix",
)
blocker = replace_required(
    blocker,
    """        }.toSet()
    )

    private fun BrowserUiCapabilityPolicy.NodeAction.androidActionId(): Int? = when (this) {
""",
    """        }.toSet(),
        hintText = hintText?.toString()
    )

    private fun BrowserUiCapabilityPolicy.NodeAction.androidActionId(): Int? = when (this) {
""",
    "WebsiteBlocker hintText mapping",
)
blocker_path.write_text(blocker)


# 3) Redirect flow: if ACTION_FOCUS is advertised but rejected, try the uniquely
# authorized click. On failure, reuse the already-visible transition curtain when
# handing off to the stable generic block surface instead of flashing a new one.
service_path = Path("app/src/main/java/com/focusguard/service/BlockingAccessibilityService.kt")
service = service_path.read_text()

service = replace_required(
    service,
    """        val activationResult = if (
            focusResult.status == WebsiteBlocker.AddressBarActionStatus.NOT_FOUND
        ) {
""",
    """        val activationResult = if (
            !focusResult.accepted &&
            focusResult.status != WebsiteBlocker.AddressBarActionStatus.AMBIGUOUS
        ) {
""",
    "focus rejection click fallback",
)

old_fail_closed = """    private fun launchOpaqueBrowserFailClosedNotice(
        browserPackageName: String,
        eventUptimeMillis: Long
    ) {
        FocusGuardLogger.log(
            \"A11y\",
            \"Bloqueio fail-closed do navegador $browserPackageName: \" +
                \"uma superfície segura não pôde ser certificada\"
        )
        launchBlockNotice(
            blockedPackage = null,
            blockedDomain = null,
            redirectBrowserPackage = null,
            eventUptimeMillis = eventUptimeMillis
        )
    }
"""
new_fail_closed = """    private fun launchOpaqueBrowserFailClosedNotice(
        browserPackageName: String,
        eventUptimeMillis: Long,
        curtainGeneration: Long = 0L
    ) {
        FocusGuardLogger.log(
            \"A11y\",
            \"Bloqueio fail-closed do navegador $browserPackageName: \" +
                \"uma superfície segura não pôde ser certificada\"
        )

        // A website transition already owns an opaque curtain. Reusing that exact
        // generation avoids a visible detach/attach flash when the browser cannot
        // complete a certifiable same-tab redirect (notably DuckDuckGo omnibar UI
        // transitions). The destination Activity acknowledges this same generation.
        val canReuseCurtain = curtainGeneration > 0L &&
            instantBlockCurtainAttached &&
            instantBlockCurtainVisible &&
            instantBlockCurtainGeneration == curtainGeneration
        if (canReuseCurtain) {
            awaitingSafeSurfaceGeneration = curtainGeneration
            val launched = runCatching {
                startActivity(
                    createBlockNoticeIntent(
                        context = this,
                        strictBlock = isPomodoroStrictActive,
                        blockedPackage = null,
                        blockedDomain = null,
                        redirectBrowserPackage = null,
                        curtainGeneration = curtainGeneration,
                        eventUptimeMillis = eventUptimeMillis
                    )
                )
            }.onFailure { error ->
                FocusGuardLogger.logError(
                    \"A11y\",
                    \"Falha ao abrir superfície fail-closed com cortina existente\",
                    error
                )
            }.isSuccess
            if (!launched) beginCurtainEvacuationBeforeHide(curtainGeneration)
            return
        }

        launchBlockNotice(
            blockedPackage = null,
            blockedDomain = null,
            redirectBrowserPackage = null,
            eventUptimeMillis = eventUptimeMillis
        )
    }
"""
service = replace_required(service, old_fail_closed, new_fail_closed, "stable fail-closed curtain")

old_transition_notice = """                    launchOpaqueBrowserFailClosedNotice(
                        browserPackageName = browserPackageName,
                        eventUptimeMillis = SystemClock.uptimeMillis()
                    )
"""
new_transition_notice = """                    launchOpaqueBrowserFailClosedNotice(
                        browserPackageName = browserPackageName,
                        eventUptimeMillis = SystemClock.uptimeMillis(),
                        curtainGeneration = transition.curtainGeneration
                    )
"""
transition_notice_count = service.count(old_transition_notice)
if transition_notice_count < 4:
    raise SystemExit(
        f"transition fail-closed calls: expected at least 4, found {transition_notice_count}"
    )
service = service.replace(old_transition_notice, new_transition_notice, transition_notice_count)
service_path.write_text(service)


# 4) Regression tests for both DuckDuckGo UI generations and the safety boundary.
test_path = Path("app/src/test/java/com/focusguard/utils/BrowserUiCapabilityPolicyTest.kt")
test = test_path.read_text()

insert_before = """    @Test
    fun `ordinary page fields are never actionable`() {
"""
duck_tests = """    @Test
    fun `DuckDuckGo legacy omnibar can be clicked before editing`() {
        val packageName = \"com.duckduckgo.mobile.android\"
        val omnibar = node(
            packageName = packageName,
            viewId = \"$packageName:id/omnibarTextInput\",
            editable = true,
            actions = setOf(NodeAction.CLICK)
        )

        val selection = BrowserUiCapabilityPolicy.resolveUniqueAddressBarNode(
            nodes = listOf(omnibar),
            expectedBrowserPackage = packageName,
            expectedWindowId = WINDOW_ID,
            requiredAction = NodeAction.CLICK
        )

        assertThat(selection.status).isEqualTo(SelectionStatus.SELECTED)
        assertThat(selection.index).isEqualTo(0)
    }

    @Test
    fun `DuckDuckGo native input is actionable only in address mode`() {
        val packageName = \"com.duckduckgo.mobile.android\"
        val addressInput = node(
            packageName = packageName,
            viewId = \"$packageName:id/inputField\",
            editable = true,
            focused = true,
            hintText = \"Pesquisar ou inserir endereço\",
            actions = setOf(NodeAction.SET_TEXT, NodeAction.IME_ENTER)
        )
        val duckAiPrompt = addressInput.copy(
            hintText = \"Pergunte qualquer coisa\"
        )

        assertThat(
            BrowserUiCapabilityPolicy.resolveUniqueAddressBarNode(
                nodes = listOf(addressInput),
                expectedBrowserPackage = packageName,
                expectedWindowId = WINDOW_ID,
                requiredAction = NodeAction.SET_TEXT
            ).status
        ).isEqualTo(SelectionStatus.SELECTED)
        assertThat(
            BrowserUiCapabilityPolicy.resolveUniqueAddressBarNode(
                nodes = listOf(duckAiPrompt),
                expectedBrowserPackage = packageName,
                expectedWindowId = WINDOW_ID,
                requiredAction = NodeAction.SET_TEXT
            ).status
        ).isEqualTo(SelectionStatus.NOT_FOUND)
    }

    @Test
    fun `generic inputField never becomes an address bar in another browser`() {
        val candidate = node(
            packageName = BROWSER_PACKAGE,
            viewId = \"$BROWSER_PACKAGE:id/inputField\",
            editable = true,
            focused = true,
            hintText = \"Search or enter address\",
            actions = setOf(NodeAction.SET_TEXT)
        )

        assertThat(
            BrowserUiCapabilityPolicy.resolveUniqueAddressBarNode(
                nodes = listOf(candidate),
                expectedBrowserPackage = BROWSER_PACKAGE,
                expectedWindowId = WINDOW_ID,
                requiredAction = NodeAction.SET_TEXT
            ).status
        ).isEqualTo(SelectionStatus.NOT_FOUND)
    }

"""
test = replace_required(test, insert_before, duck_tests + insert_before, "DuckDuckGo regression tests")

test = replace_required(
    test,
    """        text: String? = null,
        contentDescription: String? = null,
        actions: Set<NodeAction>
""",
    """        text: String? = null,
        contentDescription: String? = null,
        hintText: String? = null,
        actions: Set<NodeAction>
""",
    "test node hint parameter",
)

test = replace_required(
    test,
    """        text = text,
        contentDescription = contentDescription,
        actions = actions
""",
    """        text = text,
        contentDescription = contentDescription,
        actions = actions,
        hintText = hintText
""",
    "test node hint mapping",
)

test_path.write_text(test)


# 5) Documentation: record the browser-specific compatibility rule and the stable
# fail-closed handoff so future refactors do not regress into a flashing loop.
docs_path = Path("docs/WEBSITE_BLOCKING.md")
docs = docs_path.read_text()
marker = "## Compatibilidade com DuckDuckGo Android"
if marker not in docs:
    docs += """

## Compatibilidade com DuckDuckGo Android

O DuckDuckGo Android usa o pacote `com.duckduckgo.mobile.android` e, conforme a geração da interface, pode expor a barra como `omnibarTextInput` ou como o campo nativo `inputField`. O FocusGuard trata `omnibarTextInput` como uma barra que pode exigir `ACTION_CLICK` antes de aceitar `ACTION_SET_TEXT`. O id genérico `inputField` só é autorizado para automação no pacote oficial do DuckDuckGo e apenas quando o próprio nó se identifica semanticamente como campo de endereço (por exemplo, `Search or enter address` / `Pesquisar ou inserir endereço`); campos de Duck.ai com o mesmo id continuam rejeitados.

Se a neutralização na mesma aba ainda falhar, o handoff fail-closed reutiliza a geração da cortina já visível. Isso evita destacar/desanexar e recriar a cortina durante a falha, reduzindo o efeito de tela de bloqueio piscando enquanto a superfície genérica segura assume o primeiro plano.
"""
    docs_path.write_text(docs)
