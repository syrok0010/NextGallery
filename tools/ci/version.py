"""Чтение версии APK и её повышение относительно опубликованного релиза."""

import argparse
from pathlib import Path
import re


VERSION_FILE = Path(__file__).resolve().parents[2] / "version.properties"
MAX_CODE = 2_100_000_000


def version_tuple(value):
    if not re.fullmatch(r"(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)", value):
        raise ValueError("Версия должна иметь вид X.Y.Z без префикса v")
    return tuple(map(int, value.split(".")))


def read_version(path=VERSION_FILE):
    values = dict(line.split("=", 1) for line in path.read_text().splitlines() if line.strip())
    name, code = values["versionName"], int(values["versionCode"])
    version_tuple(name)
    if not 1 <= code <= MAX_CODE:
        raise ValueError("Недопустимый Android versionCode")
    return name, code


def next_version(base, part):
    major, minor, patch = version_tuple(base)
    if part == "major":
        return f"{major + 1}.0.0"
    if part == "minor":
        return f"{major}.{minor + 1}.0"
    if part == "patch":
        return f"{major}.{minor}.{patch + 1}"
    raise ValueError("Выберите major, minor или patch")


def bump(base, part, path=VERSION_FILE):
    _, code = read_version(path)
    name = next_version(base, part)
    if code == MAX_CODE:
        raise ValueError("Исчерпан диапазон versionCode")
    path.write_text(f"versionName={name}\nversionCode={code + 1}\n")
    return name, code + 1


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest="command", required=True)
    commands.add_parser("metadata")
    release = commands.add_parser("bump")
    release.add_argument("--base", required=True)
    release.add_argument("--part", choices=["major", "minor", "patch"], required=True)
    args = parser.parse_args()
    name, code = bump(args.base, args.part) if args.command == "bump" else read_version()
    print(f"version={name}\ncode={code}")


if __name__ == "__main__":
    main()
