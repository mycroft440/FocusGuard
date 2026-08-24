# FocusGuard performance validation

This document is the reproducible validation path for the self-protection fast path.
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

The Gradle Managed Device is `pixel6Api35` (Pixel 6, AOSP, API 35). Generated profile
output is copied by the Baseline Profile plugin under the app's generated baseline
profile source directory. Review the generated rules before committing them; the
manual hard-block rules remain intentionally small and should not be deleted merely
because a generated startup profile exists.

## 2. Startup Macrobenchmark

The producer module contains two cold-start measurements:

- `coldStartupNoCompilation`: no compilation/profile assistance;
- `coldStartupBaselineProfile`: Baseline Profile required.

For a connected physical device:

```bash
./gradlew :baselineprofile:connectedCheck \
  -Pandroid.testInstrumentationRunnerArguments.class=com.focusguard.baselineprofile.StartupBenchmark
```

Macrobenchmark JSON and Perfetto traces are copied into the module's
`build/outputs/connected_android_test_additional_output/` tree.

Summarize the raw run arrays with one percentile rule across every device:

```bash
python3 scripts/summarize_macrobenchmark.py \
  'baselineprofile/build/outputs/**/*-benchmarkData.json'
```

## 3. Accessibility self-protection latency

Startup time is not the primary FocusGuard safety metric. The accessibility service
already records the critical path through the `A11yLatency` tag, including:

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

## 4. Physical device matrix

Absolute performance decisions must use physical devices. Emulator results are only
for repeatable regression detection.

Minimum matrix:

| Family | Suggested coverage | Surfaces to exercise |
|---|---|---|
| Samsung / One UI | recent Galaxy, current Android | app info, accessibility, device admin, power menu |
| Google / Pixel | recent Pixel, current Android | app info, accessibility, device admin, power menu |
| Xiaomi / HyperOS | recent Xiaomi/Redmi, current Android | app info, accessibility, device admin, power menu |

For every surface record at least 100 attempts and retain both the raw log and the
summary table. Compare p50/p95/p99, but use p95/p99 to decide whether a fallback tree
query or OEM-specific view-id optimization is justified.

## 5. Acceptance gates

A performance change is ready only when all applicable gates pass:

1. Unit Tests and Android Lint are green.
2. Release APK, Debug APK and Release AAB build successfully.
3. `:baselineprofile:assemble` succeeds, preventing the benchmark harness from rotting.
4. Both Python percentile tools pass `--self-test`.
5. If the change touches the hot accessibility path, physical-device `A11yLatency`
   samples are compared before and after whenever the hardware is available.
6. Do not treat emulator Macrobenchmark numbers as end-user latency.

The repository can fully automate gates 1-4. Gate 5 requires attached physical OEM
hardware or an external device farm; no synthetic value should be substituted for it.
