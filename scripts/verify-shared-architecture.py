#!/usr/bin/env python3
"""Guard the shared module's physical/package layers and dependency direction."""

from pathlib import Path
import re
import sys

ROOT = Path(__file__).resolve().parents[1]
SRC = ROOT / "shared/src"
ANDROID_SRC = ROOT / "androidApp/src"
PREFIX = "io.github.yukiykchen.dsh."
MAX_SOURCE_LINES = 1000
GENERATED_SOURCES = {Path("ohosApp/entry/src/main/cpp/libshared_api.h")}

ALLOWED = {
    "framework": {"framework"},
    "platform": {"framework", "platform"},
    "model": {"framework", "model"},
    "data": {"framework", "platform", "model", "data"},
    "ui": {"framework", "model", "data", "ui"},
    "feature.connection": {"framework", "platform", "model", "data", "ui", "feature.connection"},
    "feature.home": {
        "framework", "platform", "model", "data", "ui",
        "feature.connection", "feature.home",
    },
    "feature.webview": {"framework", "platform", "model", "data", "ui", "feature.webview"},
}


def check_line_count(path: Path, source_root: Path):
    if len(path.read_text().splitlines()) > MAX_SOURCE_LINES:
        return [f"{path.relative_to(source_root).as_posix()}: file exceeds {MAX_SOURCE_LINES} lines"]
    return []


def auxiliary_sources(root: Path):
    """Include native code and build/test scripts, not compiler output or dependencies."""
    files = set()
    for folder, suffixes in [
        ("iosApp/iosApp", {".swift", ".m", ".mm", ".h"}),
        ("ohosApp/entry/src", {".ets", ".c", ".cpp", ".h"}),
        ("scripts", {".swift", ".cjs", ".py", ".sh", ".m", ".h"}),
    ]:
        files.update(path for path in (root / folder).rglob("*") if path.suffix in suffixes)
    for folder in [".", "shared", "androidApp", "ohosApp", "ohosApp/entry"]:
        files.update(path for path in (root / folder).glob("*") if path.suffix in {".kts", ".ts", ".sh"})
    files.update((root / "iosApp/Podspecs").glob("*.podspec"))
    if (root / "iosApp/Podfile").is_file():
        files.add(root / "iosApp/Podfile")
    return sorted(path for path in files if path.is_file() and path.relative_to(root) not in GENERATED_SOURCES)


def check_file(path: Path, source_root: Path, android=False):
    """Check physical packages and declared imports, not Kotlin expression syntax."""
    errors = check_line_count(path, source_root)
    relative = path.relative_to(source_root)
    label = relative.as_posix()
    text = path.read_text()
    if len(relative.parts) < 4 or relative.parts[1] not in {"java", "kotlin"}:
        return errors + [f"{label}: source must be under a java/ or kotlin/ directory"]
    expected = ".".join(relative.parts[2:-1])
    package_match = re.search(r"^package[ \t]+([\w.]+)", text, re.M)
    if not package_match:
        return errors + [f"{label}: missing package declaration"]
    package = package_match.group(1)
    if package != expected:
        errors.append(f"{label}: package {package} does not match directory {expected}")
    if package != PREFIX.rstrip(".") and not package.startswith(PREFIX):
        errors.append(f"{label}: source is outside project package {PREFIX}")
    suffix = expected.removeprefix(PREFIX)
    parts = suffix.split(".")
    layer = ".".join(parts[:2]) if parts[0] == "feature" else parts[0]
    production = not android and relative.parts[0].endswith("Main")
    if production and layer not in ALLOWED:
        errors.append(f"{label}: source outside an allowed layer")

    imports = re.findall(r"^import[ \t]+([\w.*]+)(?:[ \t]+as[ \t]+(\w+))?", text, re.M)
    if len(imports) != len(set(imports)):
        errors.append(f"{label}: duplicate imports")
    for imported, _ in imports:
        if imported.startswith("com.example.dsh"):
            errors.append(f"{label}: legacy project import {imported}")
        if production and layer in {"model", "data"} and imported.startswith((
            "com.tencent.kuikly.core.views.", "com.tencent.kuikly.core.reactive.",
        )):
            errors.append(f"{label}: {layer} layer imports Kuikly UI state ({imported})")
        if not imported.startswith(PREFIX):
            continue
        target_package = imported.rsplit(".", 1)[0]
        if target_package == package:
            errors.append(f"{label}: imports its own package ({imported})")
        target = imported[len(PREFIX):].split(".")
        target_layer = ".".join(target[:2]) if target[0] == "feature" else target[0]
        if production and layer in ALLOWED and target_layer not in ALLOWED[layer]:
            errors.append(
                f"{label}: {layer} must not depend on {target_layer}"
            )
    return errors


def main():
    errors = []
    count = 0
    for source_root, android in [(SRC, False), (ANDROID_SRC, True)]:
        files = sorted(source_root.glob("**/*.kt"))
        if not files:
            errors.append(f"no Kotlin sources found under {source_root}")
        count += len(files)
        for path in files:
            errors.extend(check_file(path, source_root, android))

    native_files = auxiliary_sources(ROOT)
    for path in native_files:
        errors.extend(check_line_count(path, ROOT))

    sqlite_stores = list((SRC / "sqliteMain").glob("**/DshSqliteLocalStore.kt"))
    if len(sqlite_stores) != 1:
        errors.append("sqliteMain must contain exactly one shared DshSqliteLocalStore")

    for legacy in [SRC / "commonMain/kotlin/io/github/yukiykchen/dsh/dsh", SRC / "commonMain/kotlin/io/github/yukiykchen/dsh/base"]:
        if legacy.exists():
            errors.append(f"{legacy.relative_to(ROOT)}: legacy flat directory is back")

    if errors:
        print("Shared architecture check failed:", file=sys.stderr)
        for error in errors:
            print(f"  - {error}", file=sys.stderr)
        return 1
    print(f"Shared architecture check passed: {count} Kotlin sources, {len(native_files)} native/script sources, all <= {MAX_SOURCE_LINES} lines (generated sources excluded)")
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
