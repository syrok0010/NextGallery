"""Версии, подготовка PR и публикация неизменяемого релизного APK."""

import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile


ROOT = Path(__file__).resolve().parents[2]
VERSION_FILE = ROOT / "version.properties"
RELEASE_CERT = "b5ad593630b422ed70249353fa05c196f5c105d31375a3b7a6142fa969d5fef5"


def run(*args, **kwargs):
    return subprocess.check_output(args, text=True, **kwargs).strip()


def version_tuple(value):
    if not re.fullmatch(r"(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)", value):
        raise ValueError("Версия должна иметь вид X.Y.Z без префикса v")
    return tuple(map(int, value.split(".")))


def read_version(text=None):
    text = VERSION_FILE.read_text() if text is None else text
    values = dict(line.split("=", 1) for line in text.splitlines() if line.strip())
    name = values["versionName"]
    version_tuple(name)
    code = int(values["versionCode"])
    if not 1 <= code <= 2_100_000_000:
        raise ValueError("Недопустимый Android versionCode")
    return name, code


def next_version(current, request):
    major, minor, patch = version_tuple(current)
    if request == "patch":
        target = f"{major}.{minor}.{patch + 1}"
    elif request == "minor":
        target = f"{major}.{minor + 1}.0"
    else:
        target = request
    if version_tuple(target) <= version_tuple(current):
        raise ValueError("Новая версия должна быть больше текущей")
    return target


def api(endpoint, *args):
    return json.loads(run("gh", "api", endpoint, *args))


def existing_release(repo, tag):
    result = subprocess.run(
        ["gh", "api", f"repos/{repo}/releases/tags/{tag}"],
        capture_output=True, text=True,
    )
    if result.returncode == 0:
        return json.loads(result.stdout)
    if "(HTTP 404)" in result.stderr:
        return None
    raise RuntimeError(result.stderr)


def validate_tag(tag, sha, tags):
    if tag in tags:
        actual = run("git", "rev-parse", f"{tag}^{{commit}}")
        if actual != sha:
            raise ValueError(f"Тег {tag} уже указывает на другой commit")
    target = version_tuple(tag[1:])
    for previous in tags:
        if re.fullmatch(r"v\d+\.\d+\.\d+", previous):
            if version_tuple(previous[1:]) > target:
                raise ValueError(f"Уже существует более новая версия {previous}")


def metadata():
    name, code = read_version()
    sha = run("git", "rev-parse", "HEAD")
    values = {"version": name, "code": str(code), "tag": f"v{name}", "sha": sha}
    output = os.environ.get("GITHUB_OUTPUT")
    if output:
        with open(output, "a") as stream:
            stream.writelines(f"{key}={value}\n" for key, value in values.items())
    return values


def validate_release(repo):
    values = metadata()
    tags = run("git", "tag", "--list").splitlines()
    validate_tag(values["tag"], values["sha"], tags)
    for tag in tags:
        if not re.fullmatch(r"v\d+\.\d+\.\d+", tag) or tag == values["tag"]:
            continue
        result = subprocess.run(
            ["git", "show", f"{tag}:version.properties"], capture_output=True, text=True,
        )
        if result.returncode == 0:
            _, previous_code = read_version(result.stdout)
        else:
            # Старые теги появились до выделения version.properties.
            gradle = run("git", "show", f"{tag}:app/build.gradle.kts")
            previous_code = int(re.search(r"versionCode\s*=\s*(\d+)", gradle)[1])
        if int(values["code"]) <= previous_code:
            raise ValueError(f"versionCode должен быть больше, чем в {tag}")
    release = existing_release(repo, values["tag"])
    if release and not release["draft"]:
        raise ValueError("Релиз уже опубликован; менять его нельзя")
    if release and values["tag"] not in tags:
        raise ValueError("У существующего черновика отсутствует тег; нельзя подтвердить исходный commit")
    return values


def notes(repo, name, sha):
    tags = run("git", "tag", "--list").splitlines()
    previous = [tag for tag in tags if re.fullmatch(r"v\d+\.\d+\.\d+", tag)
                and version_tuple(tag[1:]) < version_tuple(name)]
    fields = ["-f", f"tag_name=v{name}", "-f", f"target_commitish={sha}"]
    if previous:
        fields += ["-f", f"previous_tag_name={max(previous, key=lambda tag: version_tuple(tag[1:]))}"]
    return api(f"repos/{repo}/releases/generate-notes", "-X", "POST", *fields)["body"]


def prepare(repo, request):
    current, code = read_version()
    name = next_version(current, request)
    if code == 2_100_000_000:
        raise ValueError("Исчерпан диапазон versionCode")
    branch = f"release/{name}"
    # Не переписываем существующую ветку/PR, включая параллельную ручную работу.
    if run("git", "ls-remote", "--heads", "origin", branch):
        raise ValueError(f"Ветка {branch} уже существует; используйте её PR")
    if run("git", "tag", "--list", f"v{name}"):
        raise ValueError(f"Тег v{name} уже существует")
    body = (f"Подготовка релиза **{name}**: `versionCode` {code} → {code + 1}.\n\n"
            "После слияния запустите **Release → publish** из `main`.\n\n"
            "Refs #237.\n\n"
            + notes(repo, name, run("git", "rev-parse", "HEAD")))
    run("git", "switch", "-c", branch)
    VERSION_FILE.write_text(f"versionName={name}\nversionCode={code + 1}\n")
    run("git", "add", "version.properties")
    run("git", "-c", "user.name=github-actions[bot]", "-c",
        "user.email=41898282+github-actions[bot]@users.noreply.github.com",
        "-c", "commit.gpgsign=false", "commit", "-m", f"chore: prepare release {name}")
    run("git", "push", "origin", f"HEAD:refs/heads/{branch}")
    with tempfile.NamedTemporaryFile(mode="w", suffix=".md") as body_file:
        body_file.write(body)
        body_file.flush()
        url = run("gh", "pr", "create", "--repo", repo, "--base", "main", "--head", branch,
                  "--title", f"chore: prepare release {name}", "--body-file", body_file.name)
    print(url)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as stream:
            stream.write(f"PR подготовки релиза: {url}\n")


def build_tool(name):
    sdk = Path(os.environ.get("ANDROID_HOME") or os.environ["ANDROID_SDK_ROOT"])
    versions = [path for path in (sdk / "build-tools").iterdir()
                if re.fullmatch(r"\d+\.\d+\.\d+", path.name)]
    return str(max(versions, key=lambda path: tuple(map(int, path.name.split(".")))) / name)


def verify_apk(path, package, version, code, certificate):
    certificate_output = run(build_tool("apksigner"), "verify", "--print-certs", str(path))
    certificates = re.findall(r"certificate SHA-256 digest: ([0-9a-fA-F:]+)", certificate_output)
    if [value.replace(":", "").lower() for value in certificates] != [certificate]:
        raise ValueError("APK подписан неожиданным сертификатом")
    badging = run(build_tool("aapt"), "dump", "badging", str(path))
    match = re.search(r"package: name='([^']+)' versionCode='([^']+)' versionName='([^']+)'", badging)
    if not match or match.groups() != (package, str(code), version):
        raise ValueError("Пакет или версия внутри APK не совпадают с ожидаемыми")
    if "application-debuggable" in badging:
        raise ValueError("Оптимизированный APK не должен быть debuggable")


def sign(unsigned, output):
    with tempfile.TemporaryDirectory(dir=os.environ.get("RUNNER_TEMP")) as directory:
        key = Path(directory) / "release.p12"
        key.write_bytes(base64.b64decode(os.environ["RELEASE_KEY_BASE64"], validate=True))
        key.chmod(0o600)
        run(build_tool("apksigner"), "sign", "--ks", str(key), "--ks-type", "PKCS12",
            "--ks-key-alias", "nextgallery", "--ks-pass", "env:RELEASE_KEY_PASSWORD",
            "--key-pass", "env:RELEASE_KEY_PASSWORD", "--out", str(output), str(unsigned))


def publish(repo, unsigned, prerelease):
    values = validate_release(repo)
    name, tag, sha = values["version"], values["tag"], values["sha"]
    directory = ROOT / "dist"
    directory.mkdir(exist_ok=True)
    apk = directory / f"NextGallery-{name}.apk"
    checksums = directory / "SHA256SUMS"
    release = existing_release(repo, tag)
    assets = {asset["name"] for asset in release["assets"]} if release else set()
    if apk.name in assets:
        # Незавершённый выпуск продолжается с уже выложенным APK: его не пересобираем.
        run("gh", "release", "download", tag, "--repo", repo, "--pattern", apk.name,
            "--dir", str(directory))
    else:
        sign(unsigned, apk)
    verify_apk(apk, "com.syrok0010.nextgallery", name, values["code"], RELEASE_CERT)
    checksum = f"{hashlib.sha256(apk.read_bytes()).hexdigest()}  {apk.name}\n"
    if "SHA256SUMS" in assets:
        run("gh", "release", "download", tag, "--repo", repo, "--pattern", "SHA256SUMS",
            "--dir", str(directory))
        if checksums.read_text() != checksum:
            raise ValueError("Сохранённая SHA256SUMS не соответствует APK; ничего не заменено")
    else:
        checksums.write_text(checksum)
    if not release:
        if not run("git", "tag", "--list", tag):
            api(f"repos/{repo}/git/refs", "-X", "POST", "-f", f"ref=refs/tags/{tag}", "-f", f"sha={sha}")
        body = f"Версия `{name}`, code `{values['code']}`, commit `{sha}`.\n\n" + notes(repo, name, sha)
        body_file = directory / "release-notes.md"
        body_file.write_text(body)
        args = ["gh", "release", "create", tag, "--repo", repo, "--verify-tag", "--draft",
                "--title", f"NextGallery {tag}", "--notes-file", str(body_file)]
        if prerelease:
            args.append("--prerelease")
        run(*args)
    # Никогда --clobber: существующие файлы и опубликованные релизы неизменяемы.
    for path in (apk, checksums):
        if path.name not in assets:
            run("gh", "release", "upload", tag, str(path), "--repo", repo)
    release = existing_release(repo, tag)
    with open(os.environ.get("GITHUB_STEP_SUMMARY", os.devnull), "a") as stream:
        stream.write(f"Черновик релиза: {release['html_url']}\n\n"
                     f"Commit: `{sha}`. Проверьте APK и опубликуйте **этот же файл**.\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=["metadata", "prepare", "validate", "publish"])
    parser.add_argument("--version", default="patch")
    parser.add_argument("--unsigned", type=Path)
    parser.add_argument("--prerelease", choices=["true", "false"], default="true")
    args = parser.parse_args()
    os.chdir(ROOT)
    if args.command == "metadata":
        metadata()
        return
    if os.environ.get("GITHUB_REF") != "refs/heads/main":
        raise ValueError("Подготовка и выпуск релиза разрешены только из main")
    repo = os.environ["GH_REPO"]
    if args.command == "prepare":
        prepare(repo, args.version)
    elif args.command == "validate":
        validate_release(repo)
    else:
        if not args.unsigned:
            parser.error("publish требует --unsigned")
        publish(repo, args.unsigned, args.prerelease == "true")


if __name__ == "__main__":
    main()
