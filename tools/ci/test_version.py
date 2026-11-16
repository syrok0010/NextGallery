"""Проверки правил версии и записи version.properties."""

from pathlib import Path
import tempfile
import unittest

import version


class VersionTests(unittest.TestCase):
    def test_major_minor_patch_reset_lower_components(self):
        self.assertEqual(version.next_version("0.3.9", "major"), "1.0.0")
        self.assertEqual(version.next_version("0.3.9", "minor"), "0.4.0")
        self.assertEqual(version.next_version("0.3.9", "patch"), "0.3.10")

    def test_invalid_base_versions_are_rejected(self):
        for base in ["v0.3.0", "00.3.0", "0.3", "0.3.0-beta", "0.3.0\nx=y", "$(id)"]:
            with self.subTest(base=base), self.assertRaises(ValueError):
                version.next_version(base, "patch")

    def test_exact_version_is_not_a_bump_option(self):
        with self.assertRaises(ValueError):
            version.next_version("0.3.0", "0.4.0")

    def test_bump_uses_published_version_and_increments_file_code(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "version.properties"
            path.write_text("versionName=0.4.0\nversionCode=3\n")
            self.assertEqual(version.bump("0.3.0", "minor", path), ("0.4.0", 4))
            self.assertEqual(version.read_version(path), ("0.4.0", 4))
            self.assertEqual(path.read_text(), "versionName=0.4.0\nversionCode=4\n")

    def test_android_code_limits(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "version.properties"
            for code in [0, -1, version.MAX_CODE + 1]:
                path.write_text(f"versionName=0.4.0\nversionCode={code}\n")
                with self.subTest(code=code), self.assertRaises(ValueError):
                    version.read_version(path)
            original = f"versionName=0.4.0\nversionCode={version.MAX_CODE}\n"
            path.write_text(original)
            with self.assertRaisesRegex(ValueError, "Исчерпан"):
                version.bump("0.3.0", "minor", path)
            self.assertEqual(path.read_text(), original)

    def test_invalid_bump_leaves_file_unchanged(self):
        with tempfile.TemporaryDirectory() as directory:
            path = Path(directory) / "version.properties"
            original = "versionName=0.4.0\nversionCode=3\n"
            path.write_text(original)
            with self.assertRaises(ValueError):
                version.bump("invalid", "minor", path)
            self.assertEqual(path.read_text(), original)


if __name__ == "__main__":
    unittest.main()
