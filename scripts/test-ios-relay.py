#!/usr/bin/env python3
"""Typecheck iOS production Relay sources and run their portable native tests on macOS."""
from pathlib import Path
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[1]
NATIVE = ROOT / "iosApp/iosApp/KuiklyExpand/Native"
SOURCES = [NATIVE / name for name in [
    "DshRelayCore.swift", "DshRelayRuntime.swift", "DshRelayTransport.swift", "DshRelaySocketIO.swift",
    "DshRelayLoopback.swift", "DshRelaySecrets.swift", "DshSealedTunnelCrypto.swift", "DshLocalNetworkAccess.swift", "DshSshKeyRecords.swift",
]]


def main():
    sdk = subprocess.check_output(["xcrun", "--sdk", "iphoneos", "--show-sdk-path"], text=True).strip()
    with tempfile.TemporaryDirectory(prefix="dsh-relay-tests-") as directory:
        subprocess.run(["xcrun", "swiftc", "-swift-version", "5", "-whole-module-optimization", "-c", "-sdk", sdk,
                        "-target", "arm64-apple-ios14.1", *map(str, SOURCES), "-o", str(Path(directory) / "Relay.o")],
                       check=True, cwd=ROOT)
        binary = Path(directory) / "RelayTests"
        tests = sorted((ROOT / "scripts/swift").glob("*.swift"))
        subprocess.run(["xcrun", "swiftc", "-swift-version", "5", *map(str, SOURCES + tests),
                        "-o", str(binary)], check=True, cwd=ROOT)
        subprocess.run([str(binary), str(ROOT)], check=True, cwd=ROOT, timeout=90)


if __name__ == "__main__":
    main()
