"""Проверки опасных границ: версии, теги, подпись и повторный выпуск."""

import hashlib
import os
from pathlib import Path
import shutil
import subprocess
import tempfile
import unittest
from unittest.mock import patch

import release


class VersionTests(unittest.TestCase):
    def test_bump(self):
        self.assertEqual(release.next_version("0.4.0", "patch"), "0.4.1")
        self.assertEqual(release.next_version("0.4.9", "minor"), "0.5.0")
        self.assertEqual(release.next_version("0.4.0", "1.0.0"), "1.0.0")

    def test_reject_invalid_or_non_increasing_versions(self):
        for target in ["0.4.0", "0.3.9", "v0.5.0", "00.5.0", "0.5.0\nx=y", "$(id)"]:
            with self.subTest(target=target), self.assertRaises(ValueError):
                release.next_version("0.4.0", target)

    def test_android_code_limits(self):
        for code in [0, -1, 2_100_000_001]:
            with self.subTest(code=code), self.assertRaises(ValueError):
                release.read_version(f"versionName=0.4.0\nversionCode={code}\n")

    def test_tag_cannot_move(self):
        with patch.object(release, "run", return_value="other-commit"):
            with self.assertRaisesRegex(ValueError, "другой commit"):
                release.validate_tag("v0.4.0", "expected-commit", ["v0.4.0"])

    def test_existing_tag_can_resume_at_same_commit(self):
        with patch.object(release, "run", return_value="expected-commit"):
            release.validate_tag("v0.4.0", "expected-commit", ["v0.3.0", "v0.4.0"])

    def test_no_release_older_than_existing_version(self):
        with self.assertRaisesRegex(ValueError, "более новая"):
            release.validate_tag("v0.4.0", "sha", ["v0.5.0"])

    def test_only_404_means_absent_release(self):
        with patch.object(release.subprocess, "run") as command:
            command.return_value = subprocess.CompletedProcess([], 1, "", "Not Found (HTTP 404)")
            self.assertIsNone(release.existing_release("owner/repo", "v0.4.0"))
            command.return_value = subprocess.CompletedProcess([], 1, "", "Forbidden (HTTP 403)")
            with self.assertRaises(RuntimeError):
                release.existing_release("owner/repo", "v0.4.0")

    def test_published_release_rejected_before_build_or_sign(self):
        values = {"version": "0.4.0", "code": "3", "tag": "v0.4.0", "sha": "sha"}
        with patch.object(release, "metadata", return_value=values), \
                patch.object(release, "run", return_value=""), \
                patch.object(release, "existing_release", return_value={"draft": False}):
            with self.assertRaisesRegex(ValueError, "уже опубликован"):
                release.validate_release("owner/repo")

    def test_code_must_increase_against_previous_tag(self):
        values = {"version": "0.4.0", "code": "3", "tag": "v0.4.0", "sha": "sha"}
        previous = subprocess.CompletedProcess([], 0, "versionName=0.3.0\nversionCode=3\n", "")
        with patch.object(release, "metadata", return_value=values), \
                patch.object(release, "run", return_value="v0.3.0"), \
                patch.object(release.subprocess, "run", return_value=previous):
            with self.assertRaisesRegex(ValueError, "versionCode"):
                release.validate_release("owner/repo")

    def test_draft_without_pinned_tag_cannot_be_resumed(self):
        values = {"version": "0.4.0", "code": "3", "tag": "v0.4.0", "sha": "sha"}
        with patch.object(release, "metadata", return_value=values), \
                patch.object(release, "run", return_value=""), \
                patch.object(release, "existing_release", return_value={"draft": True}):
            with self.assertRaisesRegex(ValueError, "отсутствует тег"):
                release.validate_release("owner/repo")


class ApkTests(unittest.TestCase):
    def test_verify_rejects_wrong_signer_package_version_and_debuggable(self):
        cert = f"Signer #1 certificate SHA-256 digest: {release.RELEASE_CERT}"
        expected = "package: name='com.syrok0010.nextgallery' versionCode='3' versionName='0.4.0'"
        cases = [
            (cert.replace(release.RELEASE_CERT, "abcd"), expected),
            (cert, expected.replace("nextgallery'", "nextgallery.preview'")),
            (cert, expected.replace("versionCode='3'", "versionCode='4'")),
            (cert, expected.replace("0.4.0", "0.4.1")),
            (cert, expected + "\napplication-debuggable"),
        ]
        for output in cases:
            with self.subTest(output=output), patch.object(release, "build_tool", side_effect=lambda name: name), \
                    patch.object(release, "run", side_effect=output), self.assertRaises(ValueError):
                release.verify_apk(Path("app.apk"), "com.syrok0010.nextgallery", "0.4.0", 3,
                                   release.RELEASE_CERT)


class ResumeTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.apk_name = "NextGallery-0.4.0.apk"
        self.original = b"original signed apk, different from a rebuild"
        self.checksum = f"{hashlib.sha256(self.original).hexdigest()}  {self.apk_name}\n"
        self.values = {"version": "0.4.0", "code": "3", "tag": "v0.4.0", "sha": "sha"}
        self.commands = []

    def command(self, *args):
        self.commands.append(args)
        if args[:3] == ("gh", "release", "download"):
            filename = args[args.index("--pattern") + 1]
            data = self.original if filename == self.apk_name else self.checksum.encode()
            (self.root / "dist" / filename).write_bytes(data)
        return ""

    def resume(self, assets):
        draft = {"draft": True, "html_url": "https://example.test/release",
                 "assets": [{"name": name} for name in assets]}
        with patch.object(release, "ROOT", self.root), \
                patch.object(release, "validate_release", return_value=self.values), \
                patch.object(release, "existing_release", return_value=draft), \
                patch.object(release, "run", side_effect=self.command), \
                patch.object(release, "verify_apk") as verify, \
                patch.object(release, "sign") as sign:
            release.publish("owner/repo", Path("rebuilt.apk"), True)
            sign.assert_not_called()
            verify.assert_called_once()

    def test_retry_after_apk_upload_keeps_original_binary_and_adds_checksum(self):
        self.resume([self.apk_name])
        self.assertEqual((self.root / "dist" / self.apk_name).read_bytes(), self.original)
        uploads = [args for args in self.commands if args[:3] == ("gh", "release", "upload")]
        self.assertEqual(len(uploads), 1)
        self.assertTrue(uploads[0][4].endswith("SHA256SUMS"))

    def test_completed_draft_is_not_modified(self):
        self.resume([self.apk_name, "SHA256SUMS"])
        self.assertFalse(any(args[:3] == ("gh", "release", "upload") for args in self.commands))

    def test_wrong_saved_checksum_is_not_replaced(self):
        self.checksum = "wrong checksum\n"
        with self.assertRaisesRegex(ValueError, "SHA256SUMS"):
            self.resume([self.apk_name, "SHA256SUMS"])
        self.assertFalse(any(args[:3] == ("gh", "release", "upload") for args in self.commands))


class PrepareIntegrationTest(unittest.TestCase):
    def test_prepare_pushes_only_version_change_and_does_not_move_main(self):
        with tempfile.TemporaryDirectory() as directory:
            directory = Path(directory)
            root = directory / "checkout"
            root.mkdir()
            remote = directory / "remote.git"
            subprocess.run(["git", "init", "--bare", str(remote)], check=True, capture_output=True)

            def git(*args):
                return subprocess.check_output(["git", "-C", str(root), *args], text=True).strip()

            git("init", "-b", "main")
            git("config", "user.name", "Test")
            git("config", "user.email", "test@example.test")
            git("config", "commit.gpgsign", "false")
            (root / "tools/ci").mkdir(parents=True)
            shutil.copy(Path(release.__file__), root / "tools/ci/release.py")
            original = "versionName=0.4.0\nversionCode=3\n"
            (root / "version.properties").write_text(original)
            git("add", ".")
            git("commit", "-m", "initial")
            git("remote", "add", "origin", str(remote))
            git("push", "origin", "main")
            before = git("rev-parse", "main")

            executable = directory / "bin"
            executable.mkdir()
            gh = executable / "gh"
            gh.write_text("#!/usr/bin/env python3\nimport sys\n"
                          "if sys.argv[1] == 'api': print('{\"body\":\"Changes\"}')\n"
                          "else: print('https://example.test/pull/1')\n")
            gh.chmod(0o755)
            environment = os.environ | {"PATH": f"{executable}:{os.environ['PATH']}",
                                        "GITHUB_REF": "refs/heads/main", "GH_REPO": "owner/repo"}
            for key in ("GITHUB_OUTPUT", "GITHUB_STEP_SUMMARY"):
                environment.pop(key, None)
            subprocess.run(["python3", str(root / "tools/ci/release.py"), "prepare", "--version", "patch"],
                           check=True, env=environment, capture_output=True, text=True)
            self.assertEqual(git("rev-parse", "main"), before)
            self.assertEqual(git("show", "main:version.properties"), original.strip())
            self.assertEqual(git("diff", "main..HEAD", "--name-only"), "version.properties")
            self.assertEqual((root / "version.properties").read_text(), "versionName=0.4.1\nversionCode=4\n")
            self.assertIn("refs/heads/release/0.4.1", git("ls-remote", "--heads", "origin"))


if __name__ == "__main__":
    unittest.main()
