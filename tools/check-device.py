#!/usr/bin/env python3
"""Run the public offline checks only on the named disposable emulator."""
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys

root = Path(__file__).resolve().parent.parent
serial = os.environ.get("YETING_QA_DEVICE", "")
if not serial.startswith("emulator-"):
    sys.exit("Set YETING_QA_DEVICE to the dedicated emulator's serial.")
sdk = os.environ.get("ANDROID_HOME", os.environ.get("ANDROID_SDK_ROOT", ""))
adb = shutil.which("adb") or str(Path(sdk) / "platform-tools" / ("adb.exe" if os.name == "nt" else "adb"))


def run(*args, timeout=60):
    return subprocess.run([adb, "-s", serial, *args], check=True,
                          stdout=subprocess.PIPE, stderr=subprocess.STDOUT,
                          text=True, timeout=timeout).stdout


if run("shell", "getprop", "ro.kernel.qemu").strip() != "1":
    sys.exit("Refusing to run on a physical device.")
if run("shell", "getprop", "ro.boot.qemu.avd_name").strip() != "YeTingOpenSourceQA":
    sys.exit("Refusing to touch an AVD other than YeTingOpenSourceQA.")

for apk in ["debug/app-debug.apk", "androidTest/debug/app-debug-androidTest.apk"]:
    path = root / "android/app/build/outputs/apk" / apk
    if not path.is_file():
        sys.exit("Build :app:assembleDebug :app:assembleDebugAndroidTest first.")
    print(run("install", "-r", "-t", str(path), timeout=120), flush=True)

selectors = (root / "tools/device-tests.txt").read_text().splitlines()
output = run("shell", "am", "instrument", "-w", "-r", "-e", "class",
             ",".join(selectors), "cn.yeting.app.test/androidx.test.runner.AndroidJUnitRunner",
             timeout=900)
print(output, flush=True)
summary = re.search(r"OK \((\d+) tests?\)", output)
codes = re.findall(r"^INSTRUMENTATION_STATUS_CODE: (-?\d+)$", output, re.MULTILINE)
if not summary or int(summary.group(1)) != len(selectors) or codes.count("0") != len(selectors) or any(
        marker in output for marker in ["FAILURES!!!", "INSTRUMENTATION_FAILED", "Process crashed"]):
    sys.exit("Device checks did not complete successfully; retain the output for inspection.")
print(f"Passed {len(selectors)} selected device checks on {serial}.")
