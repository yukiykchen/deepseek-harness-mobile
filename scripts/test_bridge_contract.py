import re
import unittest
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
SHARED = ROOT / "shared/src/commonMain/kotlin/io/github/yukiykchen/dsh/framework/DshBridgeModule.kt"
ANDROID = ROOT / "androidApp/src/main/java/io/github/yukiykchen/dsh/module/KRBridgeModule.kt"
IOS = ROOT / "iosApp/iosApp/KuiklyExpand/Modules/HRBridgeModule.m"
OHOS = ROOT / "ohosApp/entry/src/main/ets/kuikly/modules/KRBridgeModule.ets"


class BridgeContractTest(unittest.TestCase):
    def setUp(self):
        self.shared = SHARED.read_text()
        self.android = ANDROID.read_text()
        self.ios = IOS.read_text()
        self.ohos = OHOS.read_text()

    def test_shared_exposes_only_the_current_capability_set(self):
        methods = set(re.findall(r'fun (\w+)\(', self.shared))
        methods -= {"moduleName", "callNativeMethod", "syncCallNativeMethod"}
        expected = {
            "copyToPasteboard", "toast", "currentTimeStamp", "closeKeyboard",
            "setSystemBarsDimmed", "pickSshKey", "importSshKey",
            "deleteSshKey", "validateSshKey",
        }
        self.assertEqual(expected, methods)

    def test_every_native_host_routes_the_same_methods(self):
        methods = [
            "copyToPasteboard", "toast", "currentTimestamp", "closeKeyboard",
            "setSystemBarsDimmed", "pickSshKey", "importSshKey",
            "deleteSshKey", "validateSshKey",
        ]
        for name, source in [("android", self.android), ("ios", self.ios), ("ohos", self.ohos)]:
            for method in methods:
                with self.subTest(host=name, method=method):
                    self.assertIn(method, source)
        for stale in ["ssoRequest", "qqLiveSSORequest", "showAlert", "dateFormatter", "startSshKeepAlive"]:
            for name, source in [("android", self.android), ("ios", self.ios), ("ohos", self.ohos)]:
                with self.subTest(host=name, stale=stale):
                    self.assertNotIn(stale, source)

    def test_unsupported_native_methods_do_not_fail_silently(self):
        self.assertIn("unsupported bridge method", self.android)
        self.assertIn("unsupported bridge method", self.ohos)


if __name__ == "__main__":
    unittest.main()
