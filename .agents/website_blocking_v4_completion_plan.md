# Website blocking v4 completion plan

## Scope
Finish the remaining implementation gaps against the reviewed v4 plan without changing behavior that is already working for browser-specific routes.

## Completed implementation
- Structural unknown-browser recognition remains `G && B && (C || (H && T))`; registered browser packages still bypass the generic detector.
- PackageManager collection has a hard deadline, one active worker, one pending slot, bounded quarantine/replacement for a timed-out Binder worker, and lease-based late-result rejection.
- Browser classification cache identity covers install/update/application state and is invalidated on package add/remove/replace/change broadcasts.
- Duplicate ResolveInfo filters are aggregated deterministically and permission-protected/non-usable Custom Tabs services do not count as public evidence.
- Same-window semantic surface transitions advance `surfaceEpoch`; stale inspection and recovery work is rejected across epoch changes while ordinary content storms remain coalesced.
- Website redirection transactions capture the production surface epoch. A newer same-window surface invalidates operational ownership while the opaque curtain remains fail-closed; only the existing independently verified safe-destination path may rebind once, including when Android reused the same numeric window id.
- Existing browser-specific selectors/redirection strategies, PASSWORD, Pomodoro and app-blocking behavior were not intentionally rewritten.

## Regression coverage
Focused tests cover duplicate filter ordering, same-window surface invalidation, recovery replacement, stalled-worker quarantine and bounded replacement, package-change cache invalidation, stale transition rejection, and verified same-window destination rebind.

## Automated validation
The code implementation passes GitHub Actions `Android CI Pro` with:
- `testDebugUnitTest` (1,118 unit tests);
- `lintDebug`;
- macrobenchmark/baseline-profile performance harness compilation and helper-script validation.

## Remaining acceptance evidence
CI/unit evidence does not prove first committed curtain frame, physical touch/keyboard/TalkBack containment, OEM behavior, or that every browser emits the same Accessibility boundary events. Physical browser/device homologation remains required before claiming universal real-device coverage.
