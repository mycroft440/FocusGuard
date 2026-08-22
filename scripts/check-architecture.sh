#!/usr/bin/env bash
set -euo pipefail

python3 -B scripts/check_architecture.py --self-test
python3 -B scripts/check_architecture.py
