"""Small routing regressions; local Git fixture, no external service or credentials."""
import subprocess
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

import ci_scope


class CiScopeTest(unittest.TestCase):
    def test_shallow_batched_push_fetches_only_missing_base_and_keeps_head(self):
        scratch = ci_scope.ROOT / "build" / "unimusic-toolchain"
        scratch.mkdir(parents=True, exist_ok=True)
        with tempfile.TemporaryDirectory(prefix="ci-scope-", dir=scratch) as folder:
            root = Path(folder).resolve()
            self.assertTrue(root.is_relative_to(scratch.resolve()))
            source, checkout = root / "source", root / "checkout"
            source.mkdir()

            def git(cwd, *args):
                result = subprocess.run(["git", "-c", "safe.directory=" + str(cwd), "-C", str(cwd), *args],
                                        capture_output=True, check=True, timeout=20)
                return result.stdout.decode("utf-8").strip()

            git(source, "init", "-b", "main")
            git(source, "config", "user.name", "CI fixture")
            git(source, "config", "user.email", "fixture@example.invalid")
            (source / "README.md").write_text("fixture\n", encoding="utf-8")
            git(source, "add", ".")
            git(source, "commit", "-m", "base")
            base = git(source, "rev-parse", "HEAD")
            code = "modules/local/" + ci_scope.JAVA + "data/recommendation/Example.kt"
            target = source / code
            target.parent.mkdir(parents=True)
            target.write_text("// fixture\n", encoding="utf-8")
            git(source, "add", ".")
            git(source, "commit", "-m", "code")
            (source / "docs").mkdir()
            (source / "docs/note.md").write_text("fixture\n", encoding="utf-8")
            git(source, "add", ".")
            git(source, "commit", "-m", "notes")
            head = git(source, "rev-parse", "HEAD")
            git(root, "-c", "protocol.file.allow=always", "clone", "--depth=2", "--branch=main", source.as_uri(), str(checkout))
            with patch.object(ci_scope, "ROOT", checkout):
                self.assertIsNone(ci_scope.read_changes(base, head))
                changed = ci_scope.read_changes(base, head, fetch_missing_base=True)
            self.assertEqual({code, "docs/note.md"}, set(changed))
            self.assertFalse(ci_scope.classify(changed)["migration_required"])
            self.assertEqual(head, git(checkout, "rev-parse", "HEAD"))
            self.assertEqual("", git(checkout, "status", "--porcelain"))

    def test_invalid_ranges_and_failed_fetch_keep_unknown_range_fallback(self):
        base, head = "1" * 40, "2" * 40
        with patch.object(ci_scope.subprocess, "run") as run:
            for invalid in [None, "0" * 40, "main", "--upload-pack=other"]:
                self.assertIsNone(ci_scope.read_changes(invalid, head, fetch_missing_base=True))
            run.assert_not_called()
        result = lambda code: subprocess.CompletedProcess([], code, stdout=b"", stderr=b"")
        with patch.object(ci_scope.subprocess, "run", side_effect=[result(128), result(0), result(128), result(128)]) as run:
            self.assertIsNone(ci_scope.read_changes(base, head, fetch_missing_base=True))
            self.assertEqual(["fetch", "--no-tags", "--depth=1", "origin", base], run.call_args.args[0][-5:])
        with patch.object(ci_scope.subprocess, "run", side_effect=[result(128), result(0), result(0)]) as run:
            self.assertIsNone(ci_scope.read_changes(base, head, fetch_missing_base=True))
            self.assertFalse(any("fetch" in item.args[0] for item in run.call_args_list))

    def test_known_changes_still_require_migrations_for_safety_inputs(self):
        for path in [".github/workflows/unimusic_smoke.yml", "tools_pub/unimusic/ci_scope.py",
                     "modules/database/src/main/java/example/entity/Track.kt", "app/build.gradle.kts"]:
            self.assertTrue(ci_scope.classify([path, "docs/note.md"])["migration_required"])
        self.assertFalse(ci_scope.classify(["docs/note.md"])["identity_only"])


if __name__ == "__main__":
    unittest.main()
