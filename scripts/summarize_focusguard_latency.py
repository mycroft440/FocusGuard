#!/usr/bin/env python3
"""Summarize FocusGuard A11yLatency log samples as p50/p95/p99.

Usage:
  adb logcat -d -v brief A11yLatency:* '*:S' | \
      python3 scripts/summarize_focusguard_latency.py
  python3 scripts/summarize_focusguard_latency.py focusguard-latency.log

The script intentionally uses only the Python standard library so it can run on
any development machine or CI worker without installing packages.
"""

from __future__ import annotations

import argparse
import math
import re
import sys
from collections import defaultdict
from pathlib import Path
from typing import Iterable

ACTION_RE = re.compile(
    r"Autoproteção: entrega=(?P<delivery>\d+)µs, "
    r"callback→cortina=(?P<callback_to_curtain>\d+)µs, "
    r"cortina→HOME=(?P<curtain_to_home>\d+)µs, total=(?P<callback_to_home>\d+)µs"
)
FRAME_RE = re.compile(
    r"Cortina frame commit: callback→frame=(?P<callback_to_frame>\d+)µs, "
    r"cortina→frame=(?P<curtain_to_frame>\d+)µs"
)

DISPLAY_NAMES = {
    "delivery": "evento→callback",
    "callback_to_curtain": "callback→cortina",
    "curtain_to_home": "cortina→HOME",
    "callback_to_home": "callback→HOME",
    "callback_to_frame": "callback→frame",
    "curtain_to_frame": "cortina→frame",
}


def percentile(values: Iterable[int], quantile: float) -> float:
    ordered = sorted(values)
    if not ordered:
        raise ValueError("percentile requires at least one sample")
    if len(ordered) == 1:
        return float(ordered[0])
    position = (len(ordered) - 1) * quantile
    lower = math.floor(position)
    upper = math.ceil(position)
    if lower == upper:
        return float(ordered[lower])
    fraction = position - lower
    return ordered[lower] + (ordered[upper] - ordered[lower]) * fraction


def parse(lines: Iterable[str]) -> dict[str, list[int]]:
    samples: dict[str, list[int]] = defaultdict(list)
    for line in lines:
        for regex in (ACTION_RE, FRAME_RE):
            match = regex.search(line)
            if match:
                for key, value in match.groupdict().items():
                    samples[key].append(int(value))
                break
    return dict(samples)


def render_markdown(samples: dict[str, list[int]]) -> str:
    rows = [
        "| Métrica | amostras | p50 | p95 | p99 | máximo |",
        "|---|---:|---:|---:|---:|---:|",
    ]
    for key in DISPLAY_NAMES:
        values = samples.get(key, [])
        if not values:
            continue
        rows.append(
            "| {name} | {count} | {p50:.0f} µs | {p95:.0f} µs | {p99:.0f} µs | {maximum} µs |".format(
                name=DISPLAY_NAMES[key],
                count=len(values),
                p50=percentile(values, 0.50),
                p95=percentile(values, 0.95),
                p99=percentile(values, 0.99),
                maximum=max(values),
            )
        )
    return "\n".join(rows)


def read_lines(paths: list[str]) -> Iterable[str]:
    if not paths:
        yield from sys.stdin
        return
    for path in paths:
        with Path(path).open("r", encoding="utf-8", errors="replace") as handle:
            yield from handle


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("files", nargs="*", help="logcat/text files; stdin when omitted")
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()

    if args.self_test:
        fixture = [
            "Autoproteção: entrega=100µs, callback→cortina=200µs, cortina→HOME=300µs, total=500µs",
            "Autoproteção: entrega=200µs, callback→cortina=400µs, cortina→HOME=600µs, total=1000µs",
            "Cortina frame commit: callback→frame=1000µs, cortina→frame=800µs",
            "Cortina frame commit: callback→frame=2000µs, cortina→frame=1600µs",
        ]
        parsed = parse(fixture)
        assert parsed["delivery"] == [100, 200]
        assert parsed["callback_to_frame"] == [1000, 2000]
        assert percentile([100, 200], 0.50) == 150.0
        print("self-test: OK")
        return 0

    samples = parse(read_lines(args.files))
    if not samples:
        print("Nenhuma amostra A11yLatency encontrada.", file=sys.stderr)
        return 2
    print(render_markdown(samples))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
