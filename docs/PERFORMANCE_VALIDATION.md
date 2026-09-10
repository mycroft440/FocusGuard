# FocusGuard performance validation

This document is the reproducible validation path for HardBlock performance work.
It separates measurements that can be automated on an AOSP emulator from absolute
latency numbers that must come from physical OEM devices.

## 1. Baseline Profile

The repository keeps the small hand-curated `app/src/main/baseline-prof.txt` for the
self-protection classes and also includes an official AndroidX Baseline Profile
producer module at `:baselineprofile`.

Generate and copy a fresh profile with:

```bash
./gradlew :app:generateBaselineProfile \
  -Pandroid.testInstrumentationRunnerArguments.androidx.benchmark.enabledRules=BaselineProfile
```

The generated profile covers startup plus the two input-heavy critical journeys used
by the production UI: the daily usage-limit editor and the final password editor.
The benchmark-only Activity that exposes those production composables is registered
only in benchmark/non-minified release manifests and is not part of the normal app
manifest.

The Gradle Managed Device is `pixel6Api35` (Pixel 6, AOSP, API 35). Review generated
rules before committing them; the manual hard-block rules remain intentionally small
and should not be deleted merely because a generated UI/startup profile exists.

## 2. Startup Macrobenchmark

The producer module contains two cold-start measurements:

- `coldStartupNoCompilation`: no compilation/profile assistance;
- `coldStartupBaselineProfile`: Baseline Profile required.

For a connected physical device:

```bash
./gradlew :baselineprofile:connectedCheck \
  -Pandroid.testInstrumentationRunnerArguments.class=com.focusguard.baselineprofile.StartupBenchmark
```

## 3. Input and IME frame Macrobenchmark

`InputFrameBenchmark` measures the two UI paths that previously showed visible lag.
It uses `FrameTimingMetric` and exercises the real production composables through the
benchmark-only host Activity:

- daily usage-limit numeric input, including focus and IME transitions;
- password input, including switching between the two secure fields and closing IME.

For keyboard regressions, repeat these journeys with HardBlock's accessibility
service enabled and a blocking session active. A run with the service disabled
cannot detect synchronous accessibility reads competing with IME/UI work. Include
an OEM keyboard on a physical device, repeated open/close cycles, and confirmation
that keystrokes appear immediately. Inspect the main-thread trace for accessibility
node requests and password-derivation/encrypted-preference work; report measured
frame/latency numbers separately from unit-test and compilation results.

Run only these frame benchmarks on a connected physical device with:

```bash
./gradlew :baselineprofile:connectedCheck \
  -Pandroid.testInstrumentationRunnerArguments.class=com.focusguard.baselineprofile.InputFrameBenchmark
```

For a broad local validation you can run startup and input benchmarks together with:

```bash
./gradlew :baselineprofile:connectedCheck
```

Macrobenchmark JSON and Perfetto traces are copied into the module's
`build/outputs/connected_android_test_additional_output/` tree.

Summarize the raw run arrays with one percentile rule across every device:

```bash
python3 scripts/summarize_macrobenchmark.py \
  'baselineprofile/build/outputs/**/*-benchmarkData.json'
```

Compare `frameOverrunMs` percentiles before and after a UI performance change. Do not
claim absolute device-wide latency improvements from emulator-only results.

## 4. Accessibility self-protection latency

Startup and Compose frame timing are not the primary HardBlock safety metric. The
accessibility service records the protection path through the `A11yLatency` tag,
including:

- event delivery → callback;
- callback → curtain request;
- curtain request → HOME;
- callback → HOME;
- callback → committed curtain frame;
- curtain request → committed frame.

Capture at least 100 blocked attempts for each protected surface on each physical
device, then summarize them:

```bash
adb logcat -c
# Exercise the protected settings surfaces repeatedly on the device.
adb logcat -d -v brief A11yLatency:* '*:S' > focusguard-latency.log
python3 scripts/summarize_focusguard_latency.py focusguard-latency.log
```

The script reports sample count, p50, p95, p99 and maximum in microseconds.

## 5. Physical device matrix

Absolute performance decisions must use physical devices. Emulator results are only
for repeatable regression detection.

Minimum matrix:

| Family | Suggested coverage | Surfaces to exercise |
|---|---|---|
| Samsung / One UI | recent Galaxy, current Android | app info, accessibility, device admin, power menu, usage-limit editor, password editor |
| Google / Pixel | recent Pixel, current Android | app info, accessibility, device admin, power menu, usage-limit editor, password editor |
| Xiaomi / HyperOS | recent Xiaomi/Redmi, current Android | app info, accessibility, device admin, power menu, usage-limit editor, password editor |

For every protected surface record at least 100 attempts and retain both the raw log
and the summary table. For UI input benchmarks retain the benchmark JSON and Perfetto
traces. Use p95/p99 to decide whether further hot-path or OEM-specific work is justified.

## 6. CI and build artifacts

`Android CI Pro` validates lint, unit tests, performance variants and the canonical
signed production build. The dedicated `Build HardBlock APK + AAB` workflow builds the
same permanent package identity and uploads a signed Release APK plus Play AAB as
GitHub Actions artifacts. It can run automatically on `main` or manually through
`workflow_dispatch`.

Both production workflows require these repository Actions secrets:

- `KEYSTORE_BASE64`
- `KEYSTORE_PASSWORD`
- `KEY_ALIAS`
- `KEY_PASSWORD`

There is deliberately no unsigned or alternate-package production fallback.

## 7. Acceptance gates

A performance change is ready only when all applicable gates pass:

1. Unit Tests and Android Lint are green.
2. Release APK and Release AAB build successfully with the permanent update key.
3. Benchmark/non-minified target variants and the Macrobenchmark/Baseline Profile module compile.
4. Both Python percentile tools pass `--self-test`.
5. If the change touches the hot accessibility path, physical-device `A11yLatency`
   samples are compared before and after whenever the hardware is available.
6. If the change touches an input/IME path, `InputFrameBenchmark` is compared on at
   least one physical device whenever hardware is available.
7. Do not treat emulator Macrobenchmark numbers as end-user latency.

The repository can fully automate gates 1-4. Gates 5-6 require attached physical OEM
hardware or an external device farm; no synthetic value should be substituted for it.
