# Website blocking v4 completion plan

## Scope
Finish the remaining implementation gaps against the reviewed v4 plan without changing behavior that is already working for browser-specific routes.

## Mapped state
- Structural unknown-browser recognition already implements `G && B && (C || (H && T))`.
- Registered browser packages bypass unknown-browser PackageManager collection.
- Browser inspection work is serialized/coalesced and uses package/window generations.
- The accessibility curtain and same-tab redirection already have dedicated runtime components.
- Redirect destination persistence/configuration already exists and is rule-aware.

## Completed implementation work
1. Harden unknown-browser structural collection without touching specific browser behavior:
   - PackageManager collection has a hard result deadline;
   - one active worker plus one pending slot keeps the live queue bounded;
   - a timed-out Binder worker is quarantined and replaced within a bounded retired-worker budget so one stuck query cannot poison discovery for the rest of the process;
   - late results are lease-invalidated before cancellation and cannot populate the cache;
   - install/update/application-enabled identity participates in cache validation;
   - package add/remove/replace/change broadcasts invalidate cached classifications immediately;
   - duplicate ResolveInfo filters are aggregated deterministically per Activity;
   - Custom Tabs evidence is accepted only from an exported/enabled provider usable by FocusGuard.
2. Strengthen browser-surface identity:
   - same-window semantic surface transitions advance `surfaceEpoch` without turning ordinary content storms into new surfaces;
   - current browser surface identity is published as primitive package/window/generation/epoch state;
   - stale inspection and recovery tokens are rejected across epoch changes.
3. Bind website redirection transactions to the originating surface:
   - production transitions capture the originating `surfaceEpoch`;
   - a same-window epoch change invalidates operational window ownership while keeping the opaque curtain fail-closed;
   - stale transitions cannot press Back, edit, confirm, or otherwise authorize effects on the newer surface;
   - the existing safe-destination verification path may rebind once after independently verifying the configured destination, including when Android reused the same numeric window id;
   - epoch-zero legacy/test callers retain the prior generation/window contract.
4. Add focused regression tests for:
   - duplicate broad/narrow filter ordering;
   - same-window surface invalidation;
   - recovery replacement on epoch changes;
   - stalled PackageManager worker quarantine and bounded replacement capacity;
   - package-change cache invalidation;
   - stale same-window transition rejection and verified same-window destination rebind.

## Automated validation
The code implementation was validated by GitHub Actions `Android CI Pro` with:
- `testDebugUnitTest` (1,118 unit tests);
- `lintDebug`;
- macrobenchmark/baseline-profile performance harness compilation and helper-script validation.

## Non-goals / remaining acceptance evidence
- Browser-specific selectors/redirection strategies that were already working were not rewritten.
- PASSWORD/Pomodoro/app-blocking behavior was not intentionally changed.
- CI/unit evidence does not prove first committed curtain frame, physical touch/keyboard/TalkBack containment, OEM behavior, or that every browser emits the same Accessibility boundary events.
- Physical browser/device homologation remains required before claiming universal real-device coverage.

## Completion criteria reached in code
- Unknown-browser collection has a hard result deadline, bounded live queue, bounded stuck-worker quarantine, and late-result rejection.
- Reinstall/update/enablement/package-state changes cannot indefinitely reuse a stale cached classification.
- Duplicate intent filters cannot make B depend on PackageManager result ordering.
- Permission-protected/non-usable Custom Tabs services do not count as public T evidence.
- Stale same-window observations, recoveries, and website transitions are invalidated by surface epoch while ordinary event storms remain coalesced.
- Focused regressions and the repository CI checks pass on the implementation branch.
