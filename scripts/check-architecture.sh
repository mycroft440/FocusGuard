#!/usr/bin/env bash
set -euo pipefail

violations=0

check_forbidden_imports() {
  local boundary="$1"
  local source_path="$2"
  local pattern="$3"
  local matches

  matches="$(git grep -n -E "$pattern" -- "$source_path" || true)"
  if [[ -n "$matches" ]]; then
    echo "Architecture boundary violated: $boundary" >&2
    echo "$matches" >&2
    violations=1
  fi
}

check_forbidden_imports \
  "domain must stay independent from Android and app layers" \
  "domain/src/main" \
  '^import (android|androidx|com\.focusguard\.(admin|database|manager|service|ui))(\.|$)'

check_forbidden_imports \
  "data must not depend on app runtime or presentation" \
  "data/src/main" \
  '^import com\.focusguard\.(admin|manager|service|ui)(\.|$)'

check_forbidden_imports \
  "platform must not depend on data or app layers" \
  "platform/src/main" \
  '^import com\.focusguard\.(admin|database|manager|service|ui)(\.|$)'

check_forbidden_imports \
  "presentation must not access Room or the database package directly" \
  "app/src/main/java/com/focusguard/ui" \
  '^import (androidx\.room|com\.focusguard\.database)(\.|$)'

check_forbidden_imports \
  "application managers must not depend on services or UI" \
  "app/src/main/java/com/focusguard/manager" \
  '^import com\.focusguard\.(service|ui)(\.|$)'

if (( violations != 0 )); then
  exit 1
fi

echo "Architecture boundaries verified."
