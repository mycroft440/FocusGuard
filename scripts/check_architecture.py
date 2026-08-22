#!/usr/bin/env python3
"""Compile-independent guards for FocusGuard module and package boundaries."""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path


ROOT = Path(__file__).resolve().parents[1]
MODULES = ("app", "data", "domain", "platform")
EXPECTED_MODULE_EDGES = {
    "app": {"data", "domain", "platform"},
    "data": {"domain"},
    "domain": set(),
    "platform": set(),
}
CORE_LAYERS = {
    "admin",
    "contract",
    "focusmode",
    "manager",
    "permissions",
    "pomodoro",
    "security",
    "service",
    "state",
    "ui",
    "uninstall",
}
REQUIRED_CORE_LAYERS = {
    "admin",
    "contract",
    "focusmode",
    "manager",
    "permissions",
    "pomodoro",
    "security",
    "service",
    "state",
    "ui",
    "uninstall",
}
PROJECT_DEPENDENCY = re.compile(r"project\(\s*[\"']:(\w+)[\"']\s*\)")
FOCUSGUARD_REFERENCE = re.compile(
    r"\bcom\.focusguard\.([A-Za-z_][A-Za-z0-9_]*)\."
)


def strongly_connected_components(graph: dict[str, set[str]]) -> list[set[str]]:
    index = 0
    stack: list[str] = []
    indexes: dict[str, int] = {}
    lowlinks: dict[str, int] = {}
    on_stack: set[str] = set()
    components: list[set[str]] = []

    def visit(node: str) -> None:
        nonlocal index
        indexes[node] = index
        lowlinks[node] = index
        index += 1
        stack.append(node)
        on_stack.add(node)

        for target in graph.get(node, set()):
            if target not in indexes:
                visit(target)
                lowlinks[node] = min(lowlinks[node], lowlinks[target])
            elif target in on_stack:
                lowlinks[node] = min(lowlinks[node], indexes[target])

        if lowlinks[node] == indexes[node]:
            component: set[str] = set()
            while True:
                member = stack.pop()
                on_stack.remove(member)
                component.add(member)
                if member == node:
                    break
            components.append(component)

    for node in sorted(graph):
        if node not in indexes:
            visit(node)
    return components


def validate_module_graph(graph: dict[str, set[str]]) -> list[str]:
    violations: list[str] = []
    for module, expected in EXPECTED_MODULE_EDGES.items():
        dependencies = graph.get(module, set())
        if dependencies != expected:
            violations.append(
                f":{module} project dependencies are {sorted(dependencies)}; "
                f"expected {sorted(expected)}"
            )
    cycles = [component for component in strongly_connected_components(graph) if len(component) > 1]
    for cycle in cycles:
        violations.append(f"Gradle module cycle detected: {sorted(cycle)}")
    return violations


def validate_layer_coverage(layers: set[str]) -> list[str]:
    missing = REQUIRED_CORE_LAYERS - layers
    if not missing:
        return []
    return [f"architecture package matrix omits required layers: {sorted(missing)}"]


def validate_package_graph(graph: dict[str, set[str]]) -> list[str]:
    violations: list[str] = []
    cycles = [
        component
        for component in strongly_connected_components(graph)
        if len(component) > 1
    ]
    for cycle in cycles:
        edges = [
            f"{source}->{target}"
            for source in sorted(cycle)
            for target in sorted(graph[source] & cycle)
        ]
        violations.append(
            f"app package cycle detected in {sorted(cycle)} via {', '.join(edges)}"
        )
    return violations


def kotlin_files(source_path: Path) -> list[Path]:
    return sorted(source_path.rglob("*.kt")) if source_path.exists() else []


def strip_kotlin_comments_and_strings(text: str) -> str:
    """Remove non-code text while preserving line positions for diagnostics."""

    def blank(match: re.Match[str]) -> str:
        return "".join("\n" if char == "\n" else " " for char in match.group(0))

    without_blocks = re.sub(r"/\*.*?\*/", blank, text, flags=re.DOTALL)
    without_lines = re.sub(r"//[^\n]*", blank, without_blocks)
    without_triples = re.sub(r'""".*?"""', blank, without_lines, flags=re.DOTALL)
    without_strings = re.sub(r'"(?:\\.|[^"\\])*"', blank, without_triples)
    return re.sub(r"'(?:\\.|[^'\\])*'", blank, without_strings)


def matching_references(source_path: Path, pattern: re.Pattern[str]) -> list[str]:
    matches: list[str] = []
    for source_file in kotlin_files(source_path):
        source_text = source_file.read_text(encoding="utf-8")
        code_lines = strip_kotlin_comments_and_strings(source_text).splitlines()
        source_lines = source_text.splitlines()
        for line_number, code_line in enumerate(code_lines, start=1):
            if pattern.search(code_line):
                relative = source_file.relative_to(ROOT)
                matches.append(
                    f"{relative}:{line_number}: {source_lines[line_number - 1].strip()}"
                )
    return matches


def check_module_graph() -> list[str]:
    violations: list[str] = []
    settings = (ROOT / "settings.gradle.kts").read_text(encoding="utf-8")
    for module in MODULES:
        if f'include(":{module}")' not in settings:
            violations.append(f"settings.gradle.kts does not include :{module}")

    graph: dict[str, set[str]] = {}
    for module in MODULES:
        build_file = ROOT / module / "build.gradle.kts"
        dependencies = set(PROJECT_DEPENDENCY.findall(build_file.read_text(encoding="utf-8")))
        graph[module] = dependencies
    violations.extend(validate_module_graph(graph))
    return violations


def check_reference_boundaries() -> list[str]:
    checks = [
        (
            "domain must stay independent from Android and app layers",
            ROOT / "domain/src/main",
            re.compile(
                r"\b(?:android|androidx)\.|"
                r"\bcom\.focusguard\.(?:admin|database|manager|service|ui)\."
            ),
        ),
        (
            "data must not depend on app runtime or presentation",
            ROOT / "data/src/main",
            re.compile(r"\bcom\.focusguard\.(?:admin|manager|service|ui)\."),
        ),
        (
            "platform must not depend on data or app layers",
            ROOT / "platform/src/main",
            re.compile(r"\bcom\.focusguard\.(?:admin|database|manager|service|ui)\."),
        ),
        (
            "presentation must not access Room or the database package directly",
            ROOT / "app/src/main/java/com/focusguard/ui",
            re.compile(r"\b(?:androidx\.room|com\.focusguard\.database)\."),
        ),
        (
            "application managers must not depend on services or UI",
            ROOT / "app/src/main/java/com/focusguard/manager",
            re.compile(r"\bcom\.focusguard\.(?:service|ui)\."),
        ),
        (
            "services and UI communicate through neutral contracts",
            ROOT / "app/src/main/java/com/focusguard/service",
            re.compile(r"\bcom\.focusguard\.ui\."),
        ),
        (
            "UI must not depend on concrete services",
            ROOT / "app/src/main/java/com/focusguard/ui",
            re.compile(r"\bcom\.focusguard\.service\."),
        ),
        (
            "security policies must not depend on managers or device administration",
            ROOT / "app/src/main/java/com/focusguard/security",
            re.compile(r"\bcom\.focusguard\.(?:admin|manager)\."),
        ),
    ]

    violations: list[str] = []
    for description, source_path, pattern in checks:
        matches = matching_references(source_path, pattern)
        if matches:
            violations.append(description + "\n  " + "\n  ".join(matches))
    return violations


def check_package_cycles() -> list[str]:
    source_root = ROOT / "app/src/main/java/com/focusguard"
    violations = validate_layer_coverage(CORE_LAYERS)
    missing_directories = sorted(
        layer for layer in REQUIRED_CORE_LAYERS if not (source_root / layer).is_dir()
    )
    if missing_directories:
        violations.append(
            "required architecture package directories are missing: "
            f"{missing_directories}"
        )

    graph = {layer: set() for layer in CORE_LAYERS}
    for layer in CORE_LAYERS:
        for source_file in kotlin_files(source_root / layer):
            text = strip_kotlin_comments_and_strings(
                source_file.read_text(encoding="utf-8")
            )
            for target in FOCUSGUARD_REFERENCE.findall(text):
                if target in CORE_LAYERS and target != layer:
                    graph[layer].add(target)

    violations.extend(validate_package_graph(graph))
    return violations


def check_room_schema() -> list[str]:
    database_source = (
        ROOT / "data/src/main/java/com/focusguard/database/AppDatabase.kt"
    ).read_text(encoding="utf-8")
    version_match = re.search(r"\bversion\s*=\s*(\d+)", database_source)
    if version_match is None:
        return ["AppDatabase version could not be determined"]

    database_version = int(version_match.group(1))
    schema_path = (
        ROOT
        / "app/schemas/com.focusguard.database.AppDatabase"
        / f"{database_version}.json"
    )
    if not schema_path.is_file():
        return [f"Room schema for AppDatabase version {database_version} is missing"]

    schema = json.loads(schema_path.read_text(encoding="utf-8"))
    schema_version = schema.get("database", {}).get("version")
    if schema_version != database_version:
        return [
            f"Room schema {schema_path.relative_to(ROOT)} declares version "
            f"{schema_version}, expected {database_version}"
        ]
    return []


def run_self_tests() -> None:
    cyclic = {"ui": {"service"}, "service": {"ui"}}
    assert {"ui", "service"} in strongly_connected_components(cyclic)
    acyclic = {"app": {"domain", "data"}, "data": {"domain"}, "domain": set()}
    assert all(len(component) == 1 for component in strongly_connected_components(acyclic))
    reverse_module_fixture = {
        module: set(dependencies)
        for module, dependencies in EXPECTED_MODULE_EDGES.items()
    }
    reverse_module_fixture["domain"].add("app")
    assert validate_module_graph(reverse_module_fixture)
    expanded_package_cycle = {layer: set() for layer in CORE_LAYERS}
    expanded_package_cycle["admin"].add("state")
    expanded_package_cycle["state"].add("focusmode")
    expanded_package_cycle["focusmode"].add("admin")
    assert validate_package_graph(expanded_package_cycle)
    assert validate_layer_coverage(CORE_LAYERS - {"state"})
    assert not validate_layer_coverage(CORE_LAYERS)
    code_fixture = strip_kotlin_comments_and_strings(
        'val concrete = com.focusguard.service.Hidden\n'
        'val ignored = "com.focusguard.ui.StringOnly"\n'
        '// com.focusguard.manager.DocumentationOnly\n'
    )
    assert FOCUSGUARD_REFERENCE.findall(code_fixture) == ["service"]
    import_fixture = "import com.focusguard.ui.LegacyScreen"
    assert FOCUSGUARD_REFERENCE.findall(import_fixture) == ["ui"]
    project_edges = set(PROJECT_DEPENDENCY.findall('implementation(project(":domain"))'))
    assert project_edges == {"domain"}
    print("Architecture guard self-tests passed.")


def main() -> int:
    if "--self-test" in sys.argv:
        run_self_tests()
        return 0

    violations = check_module_graph()
    violations.extend(check_reference_boundaries())
    violations.extend(check_package_cycles())
    violations.extend(check_room_schema())
    if violations:
        print("Architecture boundary violations:", file=sys.stderr)
        for violation in violations:
            print(f"- {violation}", file=sys.stderr)
        return 1

    print("Architecture module graph, references, FQCNs, and package SCCs verified.")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
