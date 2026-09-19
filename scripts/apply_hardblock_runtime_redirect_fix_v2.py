from pathlib import Path

SERVICE = Path("app/src/main/java/com/focusguard/service/BlockingAccessibilityService.kt")
RACE_TEST = Path("app/src/test/java/com/focusguard/service/BrowserTransitionRaceTest.kt")


def replace_once(text: str, old: str, new: str, label: str) -> str:
    count = text.count(old)
    if count != 1:
        raise SystemExit(f"{label}: expected exactly one match, found {count}")
    return text.replace(old, new, 1)


service = SERVICE.read_text()
service = replace_once(
    service,
'''            val foregroundTransitionPackage = foregroundPackageName.orEmpty()
            val transitionPackage = when {
                directPackage.isNotBlank() && websiteBlockTransitionGuard.isActive(directPackage) ->
                    directPackage
                foregroundTransitionPackage.isNotBlank() &&
                    websiteBlockTransitionGuard.isActive(foregroundTransitionPackage) ->
                    foregroundTransitionPackage
                browserInspectionEvent -> resolveEventPackageName(event)
                    .takeIf(websiteBlockTransitionGuard::isActive)
                    .orEmpty()
                else -> ""
            }
''',
'''            val foregroundTransitionPackage = foregroundPackageName.orEmpty()
            val resolvedTransitionPackage = if (
                browserInspectionEvent &&
                (directPackage.isBlank() || event.eventType == AccessibilityEvent.TYPE_WINDOWS_CHANGED)
            ) {
                resolveEventPackageName(event)
            } else {
                ""
            }
            val transitionPackage = when {
                directPackage.isNotBlank() && websiteBlockTransitionGuard.isActive(directPackage) ->
                    directPackage
                resolvedTransitionPackage.isNotBlank() &&
                    websiteBlockTransitionGuard.isActive(resolvedTransitionPackage) ->
                    resolvedTransitionPackage
                directPackage.isBlank() && foregroundTransitionPackage.isNotBlank() &&
                    websiteBlockTransitionGuard.isActive(foregroundTransitionPackage) ->
                    foregroundTransitionPackage
                else -> ""
            }
''',
    "do not route non-browser own-ui events through foreground transition",
)
SERVICE.write_text(service)

race = RACE_TEST.read_text()
race = replace_once(
    race,
'''    @Test
    fun oldTimeoutCannotLaunchFailClosedOrRecordFailure() {
        coordinator.observeWindow(pkg, 11)
        call("failClosedWebsiteTransition", transition)
        call("finishWebsiteTransition", transition)
        verify(exactly = 0) { service.startActivity(any()) }
        verify(exactly = 0) { BrowserCompatibilityStore.recordRedirectionFailure(any()) }
        assertFalse(guard.isActive(pkg))
    }
''',
'''    @Test
    fun windowChangeDuringOwnedCurtainStillAllowsFailClosedHandoff() {
        coordinator.observeWindow(pkg, 11)
        call("failClosedWebsiteTransition", transition)
        call("finishWebsiteTransition", transition)
        verify(exactly = 1) { service.startActivity(any()) }
        verify(exactly = 0) { BrowserCompatibilityStore.recordRedirectionFailure(any()) }
        assertFalse(guard.isActive(pkg))
    }

    @Test
    fun supersededCurtainPreventsStaleFailClosedHandoff() {
        coordinator.observeWindow(pkg, 11)
        ReflectionHelpers.setField(service, "instantBlockCurtainGeneration", 2L)
        call("failClosedWebsiteTransition", transition)
        call("finishWebsiteTransition", transition)
        verify(exactly = 0) { service.startActivity(any()) }
        verify(exactly = 0) { BrowserCompatibilityStore.recordRedirectionFailure(any()) }
        assertFalse(guard.isActive(pkg))
    }
''',
    "fail-closed window handoff expectations",
)
RACE_TEST.write_text(race)

print("Applied runtime redirect refinement")
