# Protected TIME block

This document defines the security boundary for **Bloquear apps por tempo**.

## Scope

Only a TIME session created by the explicit time-block configuration flow may own package-level FocusGuard self-protection.

That self-protection consists of Device Owner policies for the FocusGuard package:

- `DevicePolicyManager.setUninstallBlocked(...)`
- `DevicePolicyManager.setUserControlDisabledPackages(...)`

PASSWORD sessions, Pomodoro, daily usage limits, Safety Mode, recovery presets and generic blocking enforcement must **never arm** those package policies. Generic removal/maintenance code may clear them when performing a legitimate authenticated exit.

AccessibilityService is not used to intercept, cancel or automate an uninstall screen. It remains responsible only for the app/site enforcement already disclosed by FocusGuard.

## Activation contract

1. Device Owner must already be active.
2. The master deactivation password must already be configured.
3. The controller persists phase `PREPARING` in Device Protected Storage.
4. Package self-protection is applied and read back from Android.
5. Room creates the requested TIME session and returns its exact database id.
6. That exact id and its finite/open-ended deadline are persisted in Device Protected Storage.
7. Phase becomes `ACTIVE` only after the protected session record and Android policies agree.

If binding the exact id fails, the just-created session is rolled back and the controller reconciles policies instead of leaving a partially protected commitment.

## Reboot and expiry

Protected records live in Device Protected Storage. During Direct Boot the controller can restore package protection without opening Room. Finite records whose persisted deadline has already passed are discarded before protection is restored.

After credential unlock, Room is authoritative and the controller reconciles every persisted id with its active TIME row.

`BlockingScheduleReceiver` already schedules the next session boundary. At that boundary it runs the normal blocking reconciliation and then the TIME controller reconciliation, releasing package self-protection when the final protected TIME has expired. Exact alarms are used only when Android says exact-alarm access is available; otherwise the existing inexact `setAndAllowWhileIdle` fallback is used.

## Early revocation

The only credential exit for protected TIME is an explicit whole-session revocation with the typed master password.

- Biometrics do not revoke TIME.
- Recovery codes do not revoke TIME.
- Generic per-target removal does not revoke TIME.
- A successful master password ends the protected TIME session(s), reconciles the blocking engine, and releases package self-protection when none remain.
- Repeated wrong passwords are subject to progressive local backoff. The limiter stores only attempt counters and timestamps, never the credential.

## Android 14+ policy feedback

On API 34+, `FocusGuardPolicyUpdateReceiver` records Android policy-engine callbacks for package uninstall blocking and user-control disabling. This complements, rather than replaces, direct policy read-back.

## Health signals

The time-block setup can expose:

- Device Owner ready / missing
- master password configured / missing
- current persisted protection phase
- Android uninstall policy read-back
- Android user-control policy read-back
- latest Android 14+ policy result, when available

## Release criteria

Do not merge a change to this subsystem unless all of the following pass:

- unit tests
- Android Lint
- release APK compilation
- release AAB compilation
- explicit tests proving PASSWORD and Pomodoro do not opt into TIME package self-protection
- expiry and persisted-state tests
- master-password backoff tests

The Play Store policy review remains separate from technical correctness. FocusGuard must keep its Accessibility use transparent and must not use Accessibility to prevent uninstall or disable platform security controls.
