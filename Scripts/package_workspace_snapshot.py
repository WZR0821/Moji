"""Archive the actual worktree for a manually verified release, not Git HEAD.

Does not build, sign, commit, publish, or overwrite an existing archive.
Ignored local configuration, signing keys, builds and previous dist files stay
outside the allow-list produced by git. The regular release.sh clean-tree
protection remains unchanged.
"""
import argparse
import hashlib
import json
from pathlib import Path
import re
import subprocess
from datetime import datetime, timezone
import zipfile

ROOT = Path(__file__).resolve().parents[1]

def git(*args):
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True).strip()

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--github-commit", help="Optional verified published commit SHA")
    args = parser.parse_args()
    if args.github_commit and not re.fullmatch(r"[0-9a-f]{40}", args.github_commit):
        parser.error("github-commit must be a full SHA")
    version = dict(line.split("=", 1) for line in (ROOT / "version.properties").read_text().splitlines() if "=" in line)
    name = f"Moji-v{version['versionName']}-build{version['versionCode']}"
    output = ROOT / "dist/Source"
    output.mkdir(parents=True, exist_ok=True)
    archive = output / f"{name}-source.zip"
    if archive.exists():
        raise SystemExit(f"Archive already exists; not overwritten: {archive}")
    paths = sorted(set(git("ls-files", "-co", "--exclude-standard").splitlines()))
    forbidden = {"keystore.properties", "local.properties"}
    files = []
    checksums = []
    for relative in paths:
        path = ROOT / relative
        if not path.is_file() or path.is_symlink() or path.resolve().parent != (ROOT / relative).absolute().parent:
            raise SystemExit(f"Unsupported source entry: {relative}")
        if relative in forbidden or path.suffix.lower() in {".jks", ".keystore", ".p12", ".p8"} or relative.startswith(("build/", "dist/", ".git/")):
            raise SystemExit(f"Private/generated source entry rejected: {relative}")
        data = path.read_bytes()
        sha256 = hashlib.sha256(data).hexdigest()
        blob = hashlib.sha1(f"blob {len(data)}\0".encode() + data).hexdigest()
        executable = relative == "gradlew" or (relative.startswith("Scripts/") and relative.endswith(".sh"))
        files.append({"path": relative, "sha256": sha256, "git_blob": blob, "mode": "100755" if executable else "100644"})
        checksums.append(f"{sha256}  {relative}")
    provenance = {
        "version": version["versionName"], "build": int(version["versionCode"]),
        "author_display_name": "Jerry Wong", "source": "verified working-tree snapshot",
        "local_base_commit": git("rev-parse", "HEAD"), "github_commit": args.github_commit,
        "generated_at_utc": datetime.now(timezone.utc).isoformat(), "files": files,
        "excludes": "ignored local configuration/signing keys/builds/dist; remote-only historical files",
    }
    manifest = json.dumps(provenance, ensure_ascii=False, indent=2) + "\n"
    with zipfile.ZipFile(archive, "x", compression=zipfile.ZIP_DEFLATED) as zipped:
        for item in files:
            info = zipfile.ZipInfo(item["path"])
            info.external_attr = (0o100755 if item["mode"] == "100755" else 0o100644) << 16
            zipped.writestr(info, (ROOT / item["path"]).read_bytes(), compress_type=zipfile.ZIP_DEFLATED)
        zipped.writestr("WORKSPACE_PROVENANCE.json", manifest)
        zipped.writestr("SOURCE_SHA256SUMS.txt", "\n".join(checksums) + "\n")
    (output / f"{name}-workspace-provenance.json").write_text(manifest)
    print(f"Archived {len(files)} verified worktree files: {archive}")

if __name__ == "__main__":
    main()
