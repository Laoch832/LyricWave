#!/usr/bin/env python3
"""Package, sign and publish one verified build without exposing signing credentials."""
import argparse
import base64
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import tempfile

ROOT = Path(__file__).resolve().parents[2]
REPOSITORY = "Laoch832/LyricWave"
OWNER = "Laoch832"
OWNER_ID = 173591322
APP_ID = "dev.amenhancer.module"


def run(args, **kwargs):
    return subprocess.check_output(args, text=True, **kwargs).strip()


def sha256(path):
    return hashlib.sha256(Path(path).read_bytes()).hexdigest()


def version(source):
    names = re.findall(r'^\s*versionName\s*=\s*"([^"]+)"', source, re.M)
    codes = re.findall(r'^\s*versionCode\s*=\s*(\d+)\s*$', source, re.M)
    if len(names) != 1 or len(codes) != 1 or not re.fullmatch(
        r'\d+\.\d+\.\d+(?:-[0-9A-Za-z]+(?:[.-][0-9A-Za-z]+)*)?', names[0]
    ):
        raise ValueError("Expected one explicit, safe application version and version code")
    return names[0], int(codes[0])


def release_content(name):
    path = ROOT / "release-notes" / f"{name}.json"
    data = json.loads(path.read_text(encoding="utf-8"))
    if (data.get("schemaVersion") != 1 or data.get("versionName") != name or
            not isinstance(data.get("summary"), str) or not data["summary"].strip() or len(data["summary"]) > 500):
        raise ValueError("Release needs its own matching structured summary")
    changes = data.get("changes")
    if not isinstance(changes, list) or not 1 <= len(changes) <= 30:
        raise ValueError("Release needs actual per-version changes")
    for change in changes:
        if (not isinstance(change, dict) or change.get("type") not in ("fix", "feature", "improvement") or
                not isinstance(change.get("title"), str) or not 1 <= len(change["title"].strip()) <= 100 or
                not isinstance(change.get("description"), str) or not 1 <= len(change["description"].strip()) <= 1000):
            raise ValueError("Invalid structured release change")
    if not isinstance(data.get("notes", []), list) or any(not isinstance(v, str) or len(v) > 1000 for v in data.get("notes", [])):
        raise ValueError("Invalid release notes")
    return data


def render_release_notes(content, metadata, run_id):
    labels = {"fix": "修复", "feature": "新增", "improvement": "优化"}
    lines = [f"# 澜音 LyricWave {metadata['versionName']}", "", content['summary'], "", "## 本次更新", ""]
    lines.extend(f"- **{labels[c['type']]} · {c['title']}**：{c['description']}" for c in content['changes'])
    if content.get('notes'):
        lines += ["", "## 使用说明", ""] + [f"- {note}" for note in content['notes']]
    lines += ["", "## 构建验证", "", f"- versionCode：{metadata['versionCode']}",
              f"- 源码提交：{metadata['commit']}",
              f"- CI：https://github.com/{REPOSITORY}/actions/runs/{run_id}",
              "- 单元测试、Lint、构建和固定证书校验通过；真机验证范围以上述说明为准。", ""]
    return "\n".join(lines)


def require_stable(name):
    if not re.fullmatch(r"[0-9]+\.[0-9]+\.[0-9]+", name):
        raise ValueError("Only stable release versions may be published; prereleases are disabled")


def validate_metadata(data, expected_commit, source):
    name, code = version(source)
    if not re.fullmatch(r'[0-9a-f]{40}', expected_commit):
        raise ValueError("Expected a complete source commit SHA")
    if (data.get("commit"), data.get("versionName"), data.get("versionCode"),
            data.get("applicationId")) != (expected_commit, name, code, APP_ID):
        raise ValueError("Build metadata does not match this source revision")
    expected_name = f"LyricWave-{name}-unsigned.apk"
    if data.get("unsignedApk") != expected_name:
        raise ValueError("Unexpected unsigned APK filename")
    return name, code


def validate_identity(profile):
    if (profile.get("login"), profile.get("id"), profile.get("type")) != (
        OWNER, OWNER_ID, "User"
    ):
        raise ValueError("Publishing requires the personal Laoch832 account; bot accounts are refused")


def validate_signature(output, expected):
    fingerprints = re.findall(r'certificate SHA-256 digest:\s*([0-9a-fA-F]+)', output)
    if not re.fullmatch(r'[0-9a-f]{64}', expected) or not fingerprints or any(
        value.lower() != expected for value in fingerprints
    ):
        raise ValueError("APK signing certificate does not match the pinned certificate")
    if "Verified using v2 scheme (APK Signature Scheme v2): true" not in output:
        raise ValueError("APK does not have a verified v2 signature")
    if "CN=Android Debug" in output or "Number of signers: 1" not in output:
        raise ValueError("Unexpected APK signer")


def context():
    source = (ROOT / "app/build.gradle.kts").read_text()
    commit = os.environ.get("GITHUB_SHA") or run(["git", "rev-parse", "HEAD"], cwd=ROOT)
    repository = os.environ.get("GITHUB_REPOSITORY", REPOSITORY)
    if repository != REPOSITORY:
        raise ValueError("Signing and publishing are restricted to the development fork")
    return source, commit


def plan():
    source, _ = context()
    name, _ = version(source)
    tag = f"v{name}"
    ref = os.environ.get("GITHUB_REF", "")
    publishing = os.environ.get("AMPP_PUBLISH_REQUESTED") == "true"
    tagged = ref.startswith("refs/tags/")
    if tagged and ref != f"refs/tags/{tag}":
        raise ValueError("Tag must exactly match the application version")
    if publishing and ref not in ("refs/heads/main", f"refs/tags/{tag}"):
        raise ValueError("Manual publishing is allowed only from main or the version tag")
    should_build = True
    if tagged or publishing:
        result = subprocess.run(["gh", "api", f"repos/{REPOSITORY}/releases/tags/{tag}"],
                                capture_output=True, text=True)
        if result.returncode == 0:
            release = json.loads(result.stdout)
            if publishing or release["draft"]:
                raise ValueError("This version already has a release or draft; bump the version or resolve the draft")
            validate_identity(release["author"])
            # A personal-token release creates a tag push. Do not build and publish it twice.
            should_build = False
            print("Version already published; skipping the tag event generated by its release")
        elif "HTTP 404" not in result.stderr:
            raise ValueError("Unable to verify whether this release already exists")
    if should_build and (tagged or publishing):
        require_stable(name)
        release_content(name)
    output = os.environ.get("GITHUB_OUTPUT")
    if output:
        with open(output, "a") as stream:
            stream.write(f"should-build={str(should_build).lower()}\n")
    print(f"Build required: {should_build}")
    return should_build


def prepare(folder):
    source, commit = context()
    name, code = version(source)
    content = release_content(name)
    folder.mkdir(parents=True, exist_ok=True)
    apk = folder / f"LyricWave-{name}-unsigned.apk"
    shutil.copy2(ROOT / "app/build/outputs/apk/release/app-release-unsigned.apk", apk)
    data = dict(commit=commit, versionName=name, versionCode=code, applicationId=APP_ID,
                unsignedApk=apk.name, unsignedSha256=sha256(apk))
    validate_metadata(data, commit, source)
    (folder / "build-metadata.json").write_text(json.dumps(data, indent=2) + "\n")
    print(f"Prepared {apk.name} from {commit}")


def sign(folder, output):
    source, commit = context()
    data = json.loads((folder / "build-metadata.json").read_text())
    name, code = validate_metadata(data, commit, source)
    unsigned = folder / data["unsignedApk"]
    if sha256(unsigned) != data["unsignedSha256"]:
        raise ValueError("Unsigned APK checksum mismatch")
    required = ("AMPP_RELEASE_KEYSTORE_BASE64", "AMPP_RELEASE_STORE_PASSWORD",
                "AMPP_RELEASE_KEY_ALIAS", "AMPP_RELEASE_KEY_PASSWORD", "AMPP_SIGNING_CERT_SHA256")
    if any(not os.environ.get(key) for key in required):
        raise ValueError("Release signing configuration is incomplete")
    tools = Path(os.environ["ANDROID_HOME"]) / "build-tools/37.0.0"
    badging = run([str(tools / "aapt"), "dump", "badging", str(unsigned)])
    for value in (f"name='{APP_ID}'", f"versionCode='{code}'", f"versionName='{name}'"):
        if value not in badging.splitlines()[0]:
            raise ValueError("APK package/version differs from the checked source")
    output.mkdir(parents=True, exist_ok=True)
    apk = output / f"LyricWave-{name}.apk"
    with tempfile.TemporaryDirectory(prefix="ampp-sign-", dir=os.environ.get("RUNNER_TEMP")) as temp:
        key = Path(temp) / "release.jks"
        key.write_bytes(base64.b64decode(os.environ["AMPP_RELEASE_KEYSTORE_BASE64"], validate=True))
        key.chmod(0o600)
        aligned = Path(temp) / "aligned.apk"
        subprocess.run([str(tools / "zipalign"), "-P", "16", "-f", "4", str(unsigned), str(aligned)], check=True)
        subprocess.run([str(tools / "apksigner"), "sign", "--ks", str(key),
                        "--ks-key-alias", os.environ["AMPP_RELEASE_KEY_ALIAS"],
                        "--ks-pass", "env:AMPP_RELEASE_STORE_PASSWORD",
                        "--key-pass", "env:AMPP_RELEASE_KEY_PASSWORD", "--out", str(apk), str(aligned)], check=True)
        verification = run([str(tools / "apksigner"), "verify", "--verbose", "--print-certs", str(apk)])
        validate_signature(verification, os.environ["AMPP_SIGNING_CERT_SHA256"].lower())
        subprocess.run([str(tools / "zipalign"), "-c", "-P", "16", "4", str(apk)], check=True)
    # The private key and passwords are never part of any artifact or log.
    (output / "SIGNATURE.txt").write_text(verification + "\n")
    data.update(apk=apk.name, apkSha256=sha256(apk),
                certificateSha256=os.environ["AMPP_SIGNING_CERT_SHA256"].lower())
    (output / "build-metadata.json").write_text(json.dumps(data, indent=2) + "\n")
    (output / "SHA256SUMS.txt").write_text(f"{data['apkSha256']}  {apk.name}\n")
    content = release_content(name)
    client = dict(schemaVersion=1, **{key: data[key] for key in
                  ("commit", "versionName", "versionCode", "applicationId", "apk", "apkSha256", "certificateSha256")},
                  summary=content["summary"], changes=content["changes"], notes=content.get("notes", []))
    (output / "client-update.json").write_text(json.dumps(client, ensure_ascii=False, indent=2) + "\n")
    (output / "RELEASE_NOTES.md").write_text(render_release_notes(content, data, os.environ.get("GITHUB_RUN_ID", "")), encoding="utf-8")
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as stream:
            stream.write(f"### Signed APK\n\n`{apk.name}`\n\nSHA-256: `{data['apkSha256']}`\n\n"
                         f"Certificate: `{data['certificateSha256']}`\n")
    print(f"Verified fixed release signature: {apk.name}")


def publish(folder):
    source, commit = context()
    data = json.loads((folder / "build-metadata.json").read_text())
    name, code = validate_metadata(data, commit, source)
    require_stable(name)
    content = release_content(name)
    if os.environ.get("GITHUB_ACTOR") != OWNER or os.environ.get("GITHUB_TRIGGERING_ACTOR") != OWNER:
        raise ValueError("Only Laoch832 may trigger or rerun publishing")
    validate_identity(json.loads(run(["gh", "api", "user"])))
    tag = f"v{name}"
    ref = os.environ.get("GITHUB_REF", "")
    if ref not in ("refs/heads/main", f"refs/tags/{tag}"):
        raise ValueError("Release must run on main or on the exact application version tag")
    if ref.startswith("refs/tags/"):
        remote_commit = run(["gh", "api", f"repos/{REPOSITORY}/commits/{tag}", "--jq", ".sha"])
        if remote_commit != commit:
            raise ValueError("Release tag moved away from this build")
    # Never overwrite an existing version, even on reruns.
    releases = json.loads(run(["gh", "api", "--paginate", "--slurp", f"repos/{REPOSITORY}/releases?per_page=100"]))
    if any(release["tag_name"] == tag for page in releases for release in page):
        raise ValueError("This release version already exists; bump versionName and versionCode")
    previous = next((release for page in releases for release in page if not release["draft"]), None)
    if previous:
        previous_source = json.loads(run(["gh", "api", f"repos/{REPOSITORY}/contents/app/build.gradle.kts",
                                          "--method", "GET", "-f", f"ref={previous['tag_name']}"]))
        _, previous_code = version(base64.b64decode(previous_source["content"]).decode())
        if code <= previous_code:
            raise ValueError("versionCode must increase beyond the most recent published version")
    apk = folder / f"LyricWave-{name}.apk"
    if data.get("apk") != apk.name or sha256(apk) != data.get("apkSha256"):
        raise ValueError("Signed APK checksum mismatch")
    expected = os.environ.get("AMPP_SIGNING_CERT_SHA256", "").lower()
    if data.get("certificateSha256") != expected:
        raise ValueError("Release certificate metadata differs from the pinned certificate")
    validate_signature((folder / "SIGNATURE.txt").read_text(), expected)
    client = json.loads((folder / "client-update.json").read_text())
    expected_client = {**{key: data[key] for key in
                       ("applicationId", "versionName", "versionCode", "commit", "apk", "apkSha256", "certificateSha256")},
                       "schemaVersion": 1, "summary": content["summary"],
                       "changes": content["changes"], "notes": content.get("notes", [])}
    if client != expected_client:
        raise ValueError("Client update configuration differs from this signed build and release notes")
    args = ["gh", "release", "create", tag, str(apk), str(folder / "SHA256SUMS.txt"),
            str(folder / "SIGNATURE.txt"), str(folder / "build-metadata.json"), str(folder / "client-update.json"),
            "--repo", REPOSITORY, "--target", commit, "--title", f"澜音 LyricWave {name}",
            "--notes-file", str(folder / "RELEASE_NOTES.md"), "--draft"]
    subprocess.run(args, check=True)
    pages = json.loads(run(["gh", "api", "--paginate", "--slurp", f"repos/{REPOSITORY}/releases?per_page=100"]))
    release = next(release for page in pages for release in page if release["tag_name"] == tag)
    if release["author"]["login"] != OWNER or release["author"]["id"] != OWNER_ID:
        raise ValueError("Unexpected release author; release remains a draft")
    subprocess.run(["gh", "release", "edit", tag, "--repo", REPOSITORY, "--draft=false", "--prerelease=false", "--latest"], check=True)
    url = release["html_url"]
    print(url)
    summary = os.environ.get("GITHUB_STEP_SUMMARY")
    if summary:
        with open(summary, "a") as stream:
            stream.write(f"### Release\n\n[{name}]({url}), published by {OWNER}.\n")


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("command", choices=("plan", "prepare", "sign", "publish"))
    parser.add_argument("--input", type=Path, default=ROOT / "dist/unsigned")
    parser.add_argument("--output", type=Path, default=ROOT / "dist/signed")
    args = parser.parse_args()
    if args.command == "plan": plan()
    elif args.command == "prepare": prepare(args.input)
    elif args.command == "sign": sign(args.input, args.output)
    else: publish(args.input)


if __name__ == "__main__":
    main()
