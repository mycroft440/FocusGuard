from pathlib import Path


def patch(path_str: str, old: str, new: str, label: str) -> None:
    path = Path(path_str)
    source = path.read_text(encoding="utf-8")
    if new in source:
        return
    count = source.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one old block, found {count}")
    path.write_text(source.replace(old, new, 1), encoding="utf-8")


policy = "app/src/main/java/com/focusguard/utils/BrowserUiCapabilityPolicy.kt"
blocker = "app/src/main/java/com/focusguard/utils/WebsiteBlocker.kt"

patch(
    policy,
    '''                    expectedWindowId
                    httpsHandlerRecognized = httpsHandlerRecognized
''',
    '''                    expectedWindowId,
                    httpsHandlerRecognized = httpsHandlerRecognized
''',
    "resolver argument comma",
)

patch(
    policy,
    '''        val prefix = "$expectedBrowserPackage:id/"
        val viewId = node.viewIdResourceName
        return viewId.startsWith(prefix) && viewId.length > prefix.length
''',
    '''        val prefix = "$expectedBrowserPackage:id/"
        val viewId = node.viewIdResourceName
        if (!viewId.startsWith(prefix) || viewId.length <= prefix.length) return false

        val entryName = viewId.substring(prefix.length).lowercase(Locale.ROOT)
        return entryName.contains("url") || entryName.contains("uri") ||
            entryName.contains("omnibox") || entryName.contains("address") ||
            entryName.contains("location") || entryName.contains("navigation")
''',
    "semantic address-bar resource evidence",
)

patch(
    blocker,
    '''                    !isStrongAddressBarResource(
                        facts.viewIdResourceName,
                        browserPackageName
                    ) &&
''',
    '''                    !BrowserUiCapabilityPolicy.isStrongAddressBarResource(
                        facts.viewIdResourceName,
                        browserPackageName
                    ) &&
''',
    "qualify strong resource policy",
)
