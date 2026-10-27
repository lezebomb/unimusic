#!/usr/bin/env python3
"""Conservative CI routing. Unknown ranges retain the complete safety baseline."""
import argparse
import json
import re
import subprocess
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
JAVA = "src/main/java/moe/ouom/neriplayer/"
TEST = "src/test/java/moe/ouom/neriplayer/"
IDENTITY_PREFIXES = (
    "modules/model/" + JAVA + "data/model/unified/sync/",
    "modules/sync/" + JAVA + "data/sync/unified/",
    "modules/sync/" + TEST + "data/sync/unified/",
    "modules/local/" + JAVA + "data/unified/sync/",
)
IDENTITY_FILES = {
    "modules/local/" + JAVA + "data/unified/UnifiedIdentitySyncBackend.kt",
    *["modules/local/" + TEST + "data/unified/" + name for name in (
        "UnifiedIdentityCaptureRoomTest.kt", "UnifiedIdentityAliasRoomTest.kt", "IdentityConflictRoomTest.kt")],
    *["app/" + JAVA + "ui/screen/tab/settings/component/backup/" + name for name in (
        "SettingsIdentitySyncSection.kt", "IdentityConflictDialog.kt")],
}


def identity_path(path):
    if path in IDENTITY_FILES or path.startswith(IDENTITY_PREFIXES):
        return True
    if path.startswith("modules/sync/src/") and "/api/sync/" in path:
        return path.rsplit("/", 1)[-1].startswith("UnifiedIdentity") and path.endswith(".kt")
    return (path.startswith("modules/common/src/main/res/") and
            path.rsplit("/", 1)[-1].startswith("strings_unimusic_identity_") and path.endswith(".xml"))


def migration_path(path):
    if path.startswith((".github/workflows/", ".github/actions/", "build-logic/")):
        return True
    if path in {"tools_pub/unimusic/ci_scope.py", "gradle/libs.versions.toml", "settings.gradle.kts", "gradle.properties"} or path.endswith("build.gradle.kts"):
        return True
    if not path.startswith("modules/database/"):
        return False
    name = path.rsplit("/", 1)[-1]
    return (any(part in path for part in ("/schemas/", "/entity/", "/migration/")) or
            name == "NeriUserDataDatabase.kt" or "Migration" in name or "Upgrade" in name or "Schema" in name or
            "/src/androidTest/" in path or "/src/testFixtures/" in path)


def classify(paths):
    code = [p for p in paths if not p.startswith("docs/") and p != "README.md"]
    return {"identity_only": bool(code) and all(identity_path(p) for p in code),
            "migration_required": any(migration_path(p) for p in code), "changed_paths": len(paths)}


def read_changes(base, head):
    if not all(value and re.fullmatch(r"(?:[0-9a-f]{40}|[0-9a-f]{64})", value) and set(value) != {"0"}
               for value in (base, head)):
        return None
    args = ["git", "-c", "safe.directory=" + str(ROOT), "diff", "--name-only", "--no-renames", "-z", base, head, "--"]
    try:
        result = subprocess.run(args, cwd=ROOT, capture_output=True, timeout=20)
        if result.returncode:
            return None
        return [p for p in result.stdout.decode("utf-8", errors="strict").split("\0") if p]
    except (OSError, subprocess.SubprocessError, UnicodeDecodeError):
        return None


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--base")
    parser.add_argument("--head")
    parser.add_argument("--json", action="store_true")
    args = parser.parse_args()
    paths = read_changes(args.base, args.head)
    result = classify(paths) if paths is not None else {"identity_only": False, "migration_required": True, "changed_paths": 0}
    if args.json:
        print(json.dumps(result))
    else:
        for key, value in result.items():
            print(f"{key}={str(value).lower() if isinstance(value, bool) else value}")


if __name__ == "__main__":
    main()
