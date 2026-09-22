# Website blocking v4 completion plan

## Scope
Finish the remaining implementation gaps against the reviewed v4 plan without changing behavior that is already working for browser-specific routes.

## Mapped state
- Structural unknown-browser recognition already implements `G && B && (C || (H && T))`.
- Registered browser packages bypass unknown-browser PackageManager collection.
- Browser inspection work is serialized/coalesced and uses package/window generations.
- The accessibility curtain and same-tab redirection already have dedicated runtime components.
- Redirect destination persistence/configuration already exists and is rule-aware.

## Remaining implementation work
1. Harden unknown-browser structural collection without touching specific browser behavior:
   - bound PackageManager collection so a stuck Binder call cannot hold callers indefinitely;
   - keep worker concurrency bounded and reject late/stale collection results;
   - include installation generation and enabled state in the in-memory cache identity;
   - aggregate duplicate ResolveInfo filters deterministically per Activity;
   - accept Custom Tabs evidence only from an exported/enabled provider that is actually public to this app.
2. Strengthen inspection identity so same-window document/tab transitions can invalidate stale observations without treating every event as a new surface.
3. Add focused regression tests for the new invariants.
4. Run the repository CI/build/lint paths available from GitHub and review the branch diff.

## Non-goals
- Do not rewrite existing browser-specific selectors/redirection strategies that are already working.
- Do not change PASSWORD/Pomodoro/app-blocking behavior.
- Do not claim physical-device/browser homologation from unit/CI evidence; those checks remain device-only acceptance evidence.

## Completion criteria
- Unknown-browser collection has a hard result deadline and bounded worker capacity.
- Reinstall/enablement changes cannot reuse a stale positive classification from the prior installation state.
- Duplicate intent filters cannot make B depend on PackageManager result ordering.
- Permission-protected Custom Tabs services do not count as public T evidence.
- Stale same-window observations can be invalidated by an explicit surface epoch while ordinary event storms still coalesce.
- Focused tests and available CI checks pass on the implementation branch.
