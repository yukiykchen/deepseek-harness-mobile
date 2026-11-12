#!/usr/bin/env python3
"""Compile production SSH sources for iOS; run their lifecycle and real libssh2 tests on macOS."""
from pathlib import Path
import base64
import argparse
import hashlib
import json
import os
import platform
import plistlib
import shlex
import shutil
import socket
import subprocess
import tempfile
import threading
import time

import paramiko

ROOT = Path(__file__).resolve().parents[1]
NATIVE = ROOT / "iosApp/iosApp/KuiklyExpand/Native"
SOURCES = [NATIVE / name for name in ["DshSshTunnel.m", "DshSshSession.m", "DshSshForwarder.m", "DshSshKeyValidator.m"]]


class SshFixture:
    def __init__(self, directory):
        self.host_key = paramiko.RSAKey.generate(2048)
        self.identity = paramiko.RSAKey.generate(2048)
        self.identity.write_private_key_file(str(directory / "identity.pem"))
        self.identities = [self.identity]
        for name, password in [("ed25519", ""), ("encrypted-ed25519", "test-passphrase")]:
            path = directory / name
            subprocess.run(["ssh-keygen", "-q", "-t", "ed25519", "-N", password, "-f", str(path)], check=True)
            self.identities.append(paramiko.Ed25519Key.from_private_key_file(str(path), password=password or None))
        self.stopped = threading.Event()
        self.resources = []
        self.lock = threading.Lock()
        self.threads = []
        self.ssh = self.listen(self.accept_ssh)
        self.echo = self.listen(self.accept_echo)
        self.silent = self.listen(self.accept_silent)

    def retain(self, resource):
        with self.lock:
            if self.stopped.is_set():
                resource.close()
            else:
                self.resources.append(resource)
        return resource

    def listen(self, handler):
        listener = self.retain(socket.socket())
        listener.bind(("127.0.0.1", 0))
        listener.listen(32)
        listener.settimeout(0.1)

        def accept():
            while not self.stopped.is_set():
                try:
                    peer, _ = listener.accept()
                except socket.timeout:
                    continue
                except OSError:
                    break
                self.retain(peer)
                worker = threading.Thread(target=handler, args=(peer,), daemon=True)
                worker.start()
                self.threads.append(worker)

        worker = threading.Thread(target=accept, daemon=True)
        worker.start()
        self.threads.append(worker)
        return listener

    def accept_echo(self, peer):
        try:
            while data := peer.recv(1024):
                peer.sendall(data)
                time.sleep(0.0005)
        except OSError:
            pass
        finally:
            peer.close()

    def accept_silent(self, peer):
        self.stopped.wait(30)
        peer.close()

    def accept_ssh(self, peer):
        identities = self.identities
        destinations = {}

        class Server(paramiko.ServerInterface):
            def check_auth_publickey(self, username, key):
                return paramiko.AUTH_SUCCESSFUL if username == "test-user" and key in identities else paramiko.AUTH_FAILED

            def get_allowed_auths(self, username):
                return "publickey"

            def check_channel_direct_tcpip_request(self, chanid, origin, destination):
                destinations[chanid] = destination
                return paramiko.OPEN_SUCCEEDED

        transport = self.retain(paramiko.Transport(peer))
        transport.add_server_key(self.host_key)
        try:
            transport.start_server(server=Server())
            while transport.is_active() and not self.stopped.is_set():
                channel = transport.accept(0.1)
                if channel is None:
                    continue
                self.retain(channel)
                upstream = self.retain(socket.create_connection(destinations[channel.chanid], timeout=5))
                upstream.settimeout(None)

                def to_host(channel=channel, upstream=upstream):
                    try:
                        while data := channel.recv(4096):
                            upstream.sendall(data)
                        upstream.shutdown(socket.SHUT_WR)
                    except (OSError, EOFError):
                        pass

                def to_client(channel=channel, upstream=upstream):
                    try:
                        while data := upstream.recv(4096):
                            channel.sendall(data)
                        channel.shutdown_write()
                    except (OSError, EOFError):
                        pass
                    finally:
                        upstream.close()
                        channel.close()

                for fn in [to_host, to_client]:
                    worker = threading.Thread(target=fn, daemon=True)
                    worker.start()
                    self.threads.append(worker)
        except (OSError, EOFError, paramiko.SSHException):
            pass

    def close(self):
        self.stopped.set()
        with self.lock:
            resources = list(self.resources)
        for resource in resources:
            try:
                resource.close()
            except OSError:
                pass
        for worker in list(self.threads):
            worker.join(timeout=1)


def openssl_slice(pods, platform_name, simulator=False):
    bundle = pods / "OpenSSL-Universal/OpenSSL.xcframework"
    with (bundle / "Info.plist").open("rb") as source:
        metadata = plistlib.load(source)
    architecture = platform.machine()
    matches = [item for item in metadata["AvailableLibraries"] if item["SupportedPlatform"] == platform_name
               and item.get("SupportedPlatformVariant") == ("simulator" if simulator else None)
               and architecture in item["SupportedArchitectures"]]
    if len(matches) != 1:
        raise RuntimeError(f"Expected one OpenSSL {platform_name}/{architecture} slice")
    return bundle / matches[0]["LibraryIdentifier"] / matches[0]["LibraryPath"]


def compile_dependency(pods, directory, spec, sanitizer, simulator=False):
    source = pods / "DshLibssh2"
    framework = openssl_slice(pods, "ios" if simulator else "macos", simulator)
    target = []
    if simulator:
        sdk = subprocess.check_output(["xcrun", "--sdk", "iphonesimulator", "--show-sdk-path"], text=True).strip()
        target = ["-isysroot", sdk, "-target", platform.machine() + "-apple-ios14.1-simulator"]
    headers = directory / "headers"
    headers.mkdir()
    # Upstream includes openssl/foo.h; expose the XCFramework's flat public headers in that namespace.
    (headers / "openssl").symlink_to(framework / "Headers", target_is_directory=True)
    definitions = ["-D" + value for value in shlex.split(spec["pod_target_xcconfig"]["GCC_PREPROCESSOR_DEFINITIONS"])
                   if value != "$(inherited)"]
    units = sorted({path for pattern in spec["source_files"] for path in source.glob(pattern) if path.suffix == ".c"})
    if len(units) != 26:
        raise RuntimeError(f"Unexpected libssh2 compilation unit count: {len(units)}")
    objects = []
    for unit in units:
        output = directory / (unit.stem + ".o")
        subprocess.run(["xcrun", "clang", "-O1", *target, *sanitizer, *definitions, "-I", str(headers),
                        "-I", str(source / "include"), "-I", str(source / "src"), "-I", str(source / "dsh-config"),
                        "-c", str(unit), "-o", str(output)], check=True, cwd=ROOT)
        objects.append(output)
    return objects, framework


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--simulator", help="Run the native tests in this already-booted simulator UDID")
    options = parser.parse_args()
    pods = Path(os.environ.get("SSH_TEST_PODS", ROOT / "iosApp/Pods"))
    installed_spec = pods / "Local Podspecs/DshLibssh2.podspec.json"
    spec = json.loads(installed_spec.read_text())
    declared = json.loads(subprocess.check_output(["pod", "ipc", "spec", str(ROOT / "iosApp/Podspecs/DshLibssh2.podspec")], text=True))
    if spec != declared:
        raise RuntimeError("Installed SSH podspec differs from the repository; run pod install")
    if spec["version"] != "1.11.1" or spec["dependencies"]["OpenSSL-Universal"] != ["3.6.2000"]:
        raise RuntimeError("Unexpected SSH dependency versions")
    if spec["source"].get("sha256") != "d9ec76cbe34db98eec3539fe2c899d26b0c837cb3eb466a56b0f109cabf658f7":
        raise RuntimeError("SSH release archive must have the pinned digest")
    sdk = subprocess.check_output(["xcrun", "--sdk", "iphoneos", "--show-sdk-path"], text=True).strip()
    header = pods / "DshLibssh2/include"
    with tempfile.TemporaryDirectory(prefix="dsh-ssh-tests-") as name:
        directory = Path(name)
        for source in SOURCES:
            subprocess.run(["xcrun", "clang", "-fobjc-arc", "-Wall", "-Wextra", "-Werror", "-isysroot", sdk,
                            "-target", "arm64-apple-ios14.1", "-I", str(header), "-c", str(source),
                            "-o", str(directory / (source.stem + ".o"))], check=True, cwd=ROOT)
        binary = directory / "SshTests"
        sanitizer = ["-fsanitize=address", "-fno-omit-frame-pointer"] if not options.simulator and os.environ.get("SSH_ADDRESS_SANITIZER") == "1" else []
        objects, framework = compile_dependency(pods, directory, spec, sanitizer, bool(options.simulator))
        openssl_headers = directory / "headers"
        target = []
        if options.simulator:
            app = directory / "SshTests.app"
            app.mkdir()
            binary = app / "SshTests"
            sim_sdk = subprocess.check_output(["xcrun", "--sdk", "iphonesimulator", "--show-sdk-path"], text=True).strip()
            target = ["-isysroot", sim_sdk, "-target", platform.machine() + "-apple-ios14.1-simulator"]
        subprocess.run(["xcrun", "clang", "-fobjc-arc", "-Wall", "-Wextra", "-Werror", "-Wno-unused-parameter",
                        *target, *sanitizer,
                        "-I", str(header), "-I", str(openssl_headers), "-I", str(NATIVE), *map(str, SOURCES),
                        str(ROOT / "scripts/objc/DshSshTests.m"), *map(str, objects), "-framework", "Foundation",
                        "-F", str(framework.parent), "-framework", "OpenSSL", "-Wl,-rpath," + str(framework.parent),
                        "-lz", "-o", str(binary)], check=True, cwd=ROOT)
        fixture = SshFixture(directory)
        try:
            config = (app if options.simulator else directory) / "fixture.json"
            config.write_text(json.dumps({"port": fixture.ssh.getsockname()[1], "echoPort": fixture.echo.getsockname()[1],
                                         "silentPort": fixture.silent.getsockname()[1], "keyPath": str(directory / "identity.pem"),
                                         "ed25519Path": str(directory / "ed25519"), "encryptedPath": str(directory / "encrypted-ed25519"),
                                         "fingerprint": "SHA256:" + base64.b64encode(hashlib.sha256(fixture.host_key.asbytes()).digest()).decode().rstrip("=")}))
            if options.simulator:
                run_simulator(options.simulator, app, config, framework)
            else:
                subprocess.run([str(binary), str(config)], check=True, cwd=ROOT, timeout=90)
        finally:
            fixture.close()


def run_simulator(device, app, config, framework):
    bundle_id = "io.github.yukiykchen.dsh.ssh-tests"
    with (app / "Info.plist").open("wb") as output:
        plistlib.dump({"CFBundleIdentifier": bundle_id, "CFBundleExecutable": "SshTests", "CFBundlePackageType": "APPL",
                       "CFBundleName": "DSH SSH Tests", "CFBundleVersion": "1", "CFBundleShortVersionString": "1.0",
                       "MinimumOSVersion": "14.1", "LSRequiresIPhoneOS": True, "UIDeviceFamily": [1, 2]}, output)
    values = json.loads(config.read_text())
    for key in ["keyPath", "ed25519Path", "encryptedPath"]:
        path = Path(values[key])
        shutil.copy2(path, app / path.name)
        values[key] = path.name
    config.write_text(json.dumps(values))
    embedded = app / "Frameworks" / "OpenSSL.framework"
    shutil.copytree(framework, embedded, symlinks=True)
    subprocess.run(["xcrun", "install_name_tool", "-add_rpath", "@executable_path/Frameworks", str(app / "SshTests")], check=True)
    for path in [embedded, app]:
        subprocess.run(["codesign", "--force", "--sign", "-", str(path)], check=True)
    subprocess.run(["xcrun", "simctl", "install", device, str(app)], check=True)
    try:
        result = subprocess.run(["xcrun", "simctl", "launch", "--console", device, bundle_id], text=True,
                                stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=90)
        print(result.stdout, end="")
        result.check_returncode()
        if "Objective-C SSH tests: 19 passed, 0 failed" not in result.stdout:
            raise RuntimeError("Simulator did not report all native SSH tests passing")
    finally:
        subprocess.run(["xcrun", "simctl", "uninstall", device, bundle_id], check=True)


if __name__ == "__main__":
    main()
