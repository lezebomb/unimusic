"""Small, reproducible UniMusic baseline. Uses only the Python standard library.

The default run inventories the toolchain and checks existing module boundaries.
--android additionally attempts a debug build and two existing database test
classes. Instrumented tests and platform-account tests remain separate gates.
"""

from __future__ import annotations

import argparse
import json
import os
from pathlib import Path
import re
import signal
import shutil
import subprocess
import sys
import time
from datetime import datetime, timezone


ROOT = Path(__file__).resolve().parents[2]


def capture(command: list[str], timeout: int = 30) -> str:
    try:
        result = subprocess.run(
            command, cwd=ROOT, text=True, encoding="utf-8", errors="replace",
            stdout=subprocess.PIPE, stderr=subprocess.STDOUT, timeout=timeout,
        )
        return result.stdout.strip()
    except (OSError, subprocess.TimeoutExpired) as error:
        return f"Unavailable: {type(error).__name__}: {error}"


def installed_directories(sdk: Path | None, directory: str) -> list[str]:
    path = sdk / directory if sdk else None
    if path is None or not path.is_dir():
        return []
    return sorted(child.name for child in path.iterdir() if child.is_dir())


def local_java17() -> Path | None:
    for candidate in sorted((ROOT / ".gradle-home/jdks").glob("jdk-17*"), reverse=True):
        if (candidate / "bin" / ("javac.exe" if os.name == "nt" else "javac")).is_file():
            return candidate
    return None


def inventory(java17_home: Path | None) -> dict:
    source = (ROOT / "build-logic/convention/src/main/kotlin/Version.kt").read_text(
        encoding="utf-8"
    )
    required = {
        name: re.search(pattern, source).group(1)
        for name, pattern in {
            "compileSdk": r"compileSdkVersion\s*=\s*(\d+)",
            "minSdk": r"minSdk\s*=\s*(\d+)",
            "targetSdk": r"targetSdk\s*=\s*(\d+)",
            "ndk": r'defaultNdkVersion\s*=\s*"([^"]+)"',
            "cmake": r'defaultCMakeVersion\s*=\s*"([^"]+)"',
        }.items()
    }
    required["javaToolchain"] = "17 (buildSrc and Android convention)"
    sdk_value = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    sdk = Path(sdk_value) if sdk_value else None
    java = shutil.which("java")
    adb = shutil.which("adb")
    adb_output = capture([adb, "devices", "-l"]) if adb else "Unavailable"
    # Record connection states without retaining device serial numbers.
    device_states = [line.split()[1] for line in adb_output.splitlines()
                     if len(line.split()) >= 2 and line.split()[1]
                     in {"device", "offline", "unauthorized", "authorizing", "recovery", "sideload"}]
    java17 = java17_home / "bin" / ("java.exe" if os.name == "nt" else "java") if java17_home else None
    return {
        "required": required,
        "javaExecutable": java,
        "javaVersion": capture([java, "-version"]) if java else "Unavailable",
        "explicitJava17Version": capture([str(java17), "-version"]) if java17 else None,
        "androidSdk": str(sdk) if sdk else None,
        "installedPlatforms": installed_directories(sdk, "platforms"),
        "installedBuildTools": installed_directories(sdk, "build-tools"),
        "installedNdk": installed_directories(sdk, "ndk"),
        "installedCmake": installed_directories(sdk, "cmake"),
        "adbDeviceStates": device_states,
    }


def run_check(name: str, command: list[str], output: Path, timeout: int) -> dict:
    started = time.monotonic()
    log_path = output / f"{name}.log"
    timed_out = False
    error = None
    return_code = None
    # Invoke the checked-in Gradle wrapper JAR directly: no cmd interpolation.
    with log_path.open("w", encoding="utf-8") as log:
        try:
            process = subprocess.Popen(command, cwd=ROOT, stdout=log, stderr=subprocess.STDOUT,
                                       start_new_session=os.name != "nt")
            try:
                return_code = process.wait(timeout=timeout)
            except subprocess.TimeoutExpired:
                timed_out = True
                if os.name == "nt":
                    subprocess.run(["taskkill", "/pid", str(process.pid), "/t", "/f"],
                                   stdout=log, stderr=subprocess.STDOUT, check=False)
                else:
                    os.killpg(process.pid, signal.SIGKILL)
                process.wait()
        except OSError as exception:
            error = str(exception)
            log.write(error + "\n")
    result = {
        "name": name,
        "status": "passed" if return_code == 0 and not timed_out else "failed",
        "exitCode": return_code,
        "elapsedSeconds": round(time.monotonic() - started, 3),
        "timedOut": timed_out,
        "error": error,
        "log": str(log_path.relative_to(ROOT)) if log_path.is_relative_to(ROOT) else str(log_path),
    }
    print(f"{name}: {result['status']} ({result['elapsedSeconds']}s); {log_path}")
    return result


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--android", action="store_true", help="Attempt debug APK + two database test classes")
    parser.add_argument("--skip-structure", action="store_true", help="Skip an already completed module-boundary check")
    parser.add_argument("--java17-home", type=Path, help="JDK 17 home for Gradle toolchain discovery")
    parser.add_argument("--output", type=Path, default=ROOT / "build/unimusic-baseline/repro")
    parser.add_argument("--timeout", type=int, default=1800, help="Maximum seconds per check")
    args = parser.parse_args()
    if args.timeout <= 0:
        parser.error("--timeout must be positive")
    if not args.android and args.skip_structure:
        parser.error("--skip-structure requires --android; do not run a no-check baseline")
    args.java17_home = args.java17_home or local_java17()
    output = args.output.resolve()
    output.mkdir(parents=True, exist_ok=True)
    report = {
        "recordedAtUtc": datetime.now(timezone.utc).isoformat(),
        "revision": capture(["git", "-c", f"safe.directory={ROOT.as_posix()}", "rev-parse", "HEAD"]),
        "environment": inventory(args.java17_home),
        "checks": [],
        "notRun": ["Android instrumented migrations", "real platform accounts", "device performance/power"],
    }
    if args.skip_structure:
        report["notRun"].append("Module boundaries (--skip-structure)")
    else:
        report["checks"].append(run_check(
            "module-boundaries", [sys.executable, "-X", "utf8", "-B", "tools_pub/quality/module_boundaries.py"], output, args.timeout
        ))
    if args.android:
        command = [
            shutil.which("java") or "java", "-classpath",
            str(ROOT / "gradle/wrapper/gradle-wrapper.jar"),
            "org.gradle.wrapper.GradleWrapperMain", "--console=plain", "--no-daemon",
        ]
        if args.java17_home:
            command.append(f"-Dorg.gradle.java.installations.paths={args.java17_home.resolve()}")
        command += [
            ":app:assembleDebug", ":database:testDebugUnitTest",
            "--tests", "*RegisteredMigrationChainTest",
            "--tests", "*NeriUserDataDatabaseVersionTest", "--continue",
        ]
        report["checks"].append(run_check("android-baseline", command, output, args.timeout))
    else:
        report["notRun"].append("Android build/JVM tests (use --android)")
    (output / "report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2) + "\n", encoding="utf-8")
    print(f"Report: {output / 'report.json'}")
    return int(any(check["status"] != "passed" for check in report["checks"]))


if __name__ == "__main__":
    raise SystemExit(main())
