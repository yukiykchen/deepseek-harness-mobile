import importlib.util
from pathlib import Path
import tempfile
import unittest
import re

spec = importlib.util.spec_from_file_location(
    "architecture", Path(__file__).with_name("verify-shared-architecture.py")
)
architecture = importlib.util.module_from_spec(spec)
spec.loader.exec_module(architecture)
PACKAGE_PATH = "io/github/yukiykchen/dsh"


class ArchitectureTest(unittest.TestCase):
    def check(self, source_set, folder, body="", package=None, android=False):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            language = "java" if android else "kotlin"
            path = root / source_set / language / PACKAGE_PATH / folder / "Probe.kt"
            path.parent.mkdir(parents=True)
            package = package or "io.github.yukiykchen.dsh." + folder.replace("/", ".")
            path.write_text(f"package {package}\n\n{body}\n")
            return architecture.check_file(path, root, android)

    def test_nested_feature_tests_and_sqlite_tests_match_physical_packages(self):
        self.assertEqual([], self.check("commonTest", "feature/home"))
        self.assertEqual([], self.check("sqliteTest", "data"))

    def test_bad_package_is_reported(self):
        errors = self.check("commonMain", "data", package="com.example.dsh")
        self.assertTrue(any("does not match" in error for error in errors))

    def test_no_source_set_can_bypass_line_budget(self):
        for source_set, android in [("commonMain", False), ("sqliteMain", False),
                                    ("sqliteTest", False), ("main", True), ("test", True)]:
            errors = self.check(source_set, "data", "// line\n" * 1001, android=android)
            self.assertTrue(any("exceeds 1000" in error for error in errors))

    def test_reverse_dependencies_include_aliases_wildcards_and_nested_types(self):
        for declaration in ["ui.*", "ui.DshWordmark as Wordmark", "ui.DshMarkdownView.Companion"]:
            errors = self.check("commonMain", "data", f"import io.github.yukiykchen.dsh.{declaration}")
            self.assertTrue(any("must not depend on ui" in error for error in errors))

    def test_pure_native_layer_rejects_ui_state(self):
        errors = self.check("sqliteMain", "data", "import com.tencent.kuikly.core.reactive.handler.observable")
        self.assertTrue(any("UI state" in error for error in errors))

    def test_native_line_budget_includes_exact_boundary(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for suffix in ["swift", "ets"]:
                path = root / f"Probe.{suffix}"
                path.write_text("// line\n" * 1000)
                self.assertEqual([], architecture.check_line_count(path, root))
                path.write_text("// line\n" * 1001)
                self.assertTrue(architecture.check_line_count(path, root))

    def test_native_and_script_selection_only_exempts_the_known_generated_header(self):
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            included = ["ohosApp/entry/src/main/cpp/native.h", "ohosApp/entry/src/main/cpp/native.cpp",
                        "iosApp/iosApp/Tunnel.m", "iosApp/iosApp/Tunnel.mm", "scripts/check.py",
                        "scripts/swift/Test.swift", "scripts/objc/Test.m", "scripts/objc/Test.h",
                        "build.gradle.kts", "ohosApp/hvigorfile.ts", "iosApp/Podfile", "iosApp/Podspecs/Lib.podspec"]
            excluded = ["ohosApp/entry/src/main/cpp/libshared_api.h", "shared/build/generated/File.kt",
                        "ohosApp/oh_modules/dependency/index.ets"]
            for name in included + excluded:
                path = root / name
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text("// source\n")
            found = {path.relative_to(root).as_posix() for path in architecture.auxiliary_sources(root)}
            self.assertEqual(set(included), found)

    def test_duplicates_and_legacy_imports_are_reported(self):
        errors = self.check("commonMain", "data", "import com.example.dsh.Foo\nimport com.example.dsh.Foo")
        self.assertTrue(any("duplicate imports" in error for error in errors))
        self.assertTrue(any("legacy project import" in error for error in errors))

    def test_extension_imports_are_not_misclassified_as_unused(self):
        self.assertEqual([], self.check("commonMain", "data", "import io.github.yukiykchen.dsh.model.toRemoteMessage\n// used as receiver.toRemoteMessage()"))

    def test_theme_governed_screens_have_no_raw_color_literals(self):
        root = Path(__file__).resolve().parents[1]
        governed = [
            "shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/ui/DshTheme.kt",
            "shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/feature/connection/DshConnectionPalette.kt",
            "shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/feature/home/DshConnectionDialogs.kt",
            "shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/feature/home/DshConversationPanels.kt",
            "shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/feature/home/DshHomeContent.kt",
        ]
        for name in governed:
            source = (root / name).read_text()
            if name.endswith("DshTheme.kt"):
                continue
            self.assertFalse(re.search(r"Color\(0x[0-9A-Fa-f]{6,8}\)", source), name)

    def test_deprecated_global_pager_helpers_are_gone(self):
        root = Path(__file__).resolve().parents[1]
        framework = root / "shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/framework"
        self.assertFalse((framework / "DshPagerUtils.kt").exists())
        self.assertFalse((framework / "DshPagerIdKtx.kt").exists())


if __name__ == "__main__":
    unittest.main()
