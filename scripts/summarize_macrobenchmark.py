#!/usr/bin/env python3
"""Summarize Android Macrobenchmark JSON run arrays as p50/p95/p99.

Example:
  python3 scripts/summarize_macrobenchmark.py \
    baselineprofile/build/outputs/**/*-benchmarkData.json

Macrobenchmark already reports aggregate values, but this script intentionally
recomputes percentiles from the raw `runs` arrays so Samsung/Pixel/Xiaomi results
can be compared with one identical rule.
"""

from __future__ import annotations

import argparse
import glob
import json
import math
import sys
from pathlib import Path
from typing import Iterable


def percentile(values: Iterable[float], q: float) -> float:
    ordered = sorted(float(value) for value in values)
    if not ordered:
        raise ValueError("percentile requires samples")
    if len(ordered) == 1:
        return ordered[0]
    position = (len(ordered) - 1) * q
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return ordered[lower]
    fraction = position - lower
    return ordered[lower] + (ordered[upper] - ordered[lower]) * fraction


def expand_paths(patterns: list[str]) -> list[Path]:
    resolved: list[Path] = []
    for pattern in patterns:
        matches = glob.glob(pattern, recursive=True)
        if matches:
            resolved.extend(Path(match) for match in matches)
        else:
            candidate = Path(pattern)
            if candidate.is_file():
                resolved.append(candidate)
    return sorted(set(resolved))


def numeric_runs(metric: object) -> list[float]:
    if not isinstance(metric, dict):
        return []
    runs = metric.get("runs")
    if not isinstance(runs, list):
        return []
    return [float(value) for value in runs if isinstance(value, (int, float))]


def summarize_file(path: Path) -> list[tuple[str, str, int, float, float, float, float]]:
    payload = json.loads(path.read_text(encoding="utf-8"))
    benchmarks = payload.get("benchmarks", [])
    rows: list[tuple[str, str, int, float, float, float, float]] = []
    for benchmark in benchmarks if isinstance(benchmarks, list) else []:
        if not isinstance(benchmark, dict):
            continue
        benchmark_name = str(benchmark.get("name") or benchmark.get("className") or path.stem)
        metrics = benchmark.get("metrics", {})
        if not isinstance(metrics, dict):
            continue
        for metric_name, metric in metrics.items():
            values = numeric_runs(metric)
            if not values:
                continue
            rows.append(
                (
                    benchmark_name,
                    str(metric_name),
                    len(values),
                    percentile(values, 0.50),
                    percentile(values, 0.95),
                    percentile(values, 0.99),
                    max(values),
                )
            )
    return rows


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("files", nargs="*", help="JSON files or glob patterns")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        assert percentile([1, 2, 3, 4], 0.50) == 2.5
        assert percentile([10], 0.99) == 10.0
        print("self-test: OK")
        return 0

    paths = expand_paths(args.files)
    if not paths:
        print("Nenhum benchmarkData.json encontrado.", file=sys.stderr)
        return 2

    rows: list[tuple[str, str, int, float, float, float, float]] = []
    for path in paths:
        rows.extend(summarize_file(path))

    if not rows:
        print("Nenhuma métrica com raw runs encontrada.", file=sys.stderr)
        return 2

    print("| Benchmark | Métrica | n | p50 | p95 | p99 | máximo |")
    print("|---|---|---:|---:|---:|---:|---:|")
    for benchmark, metric, count, p50, p95, p99, maximum in rows:
        print(
            f"| {benchmark} | {metric} | {count} | {p50:.3f} | "
            f"{p95:.3f} | {p99:.3f} | {maximum:.3f} |"
        )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
