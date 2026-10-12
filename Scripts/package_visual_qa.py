"""Package only the expected final screenshot matrix, never a provisional subset."""
import hashlib
import json
import math
from pathlib import Path
import struct
import zipfile

ROOT = Path(__file__).resolve().parents[1]

def main():
    version = dict(line.split("=", 1) for line in (ROOT / "version.properties").read_text().splitlines() if "=" in line)
    name, build = version["versionName"], int(version["versionCode"])
    evidence = ROOT / f"build/{name}-qa"
    output = ROOT / f"dist/Source/Moji-v{name}-build{build}-visual-qa.zip"
    matrix = json.loads((evidence / "expected-matrix.json").read_text())
    if (matrix["version"], matrix["build"]) != (name, build):
        raise SystemExit("Screenshot matrix does not match the release version")
    expected = {f"{route}-{mode}.png" for mode in ("light", "dark") for route in matrix[mode]}
    for platform in ("ios", "android"):
        if {p.name for p in (evidence / platform).glob("*.png")} != expected:
            raise SystemExit(f"{platform} screenshot set does not match expected-matrix.json")
    sheet_count = sum(math.ceil(len(matrix[mode]) / 4) for mode in ("light", "dark"))
    if len(list((evidence / "comparisons").glob("sheet-*.png"))) != sheet_count:
        raise SystemExit("Contact sheets are incomplete or contain provisional leftovers")
    paths = sorted((evidence / "ios").glob("*.png")) + sorted((evidence / "android").glob("*.png"))
    paths += sorted((evidence / "comparisons").glob("*.png"))
    paths += [p for p in sorted((evidence / "manual").glob("*")) if p.suffix in {".png", ".xml", ".json"}]
    paths.append(evidence / "expected-matrix.json")
    paths += [evidence / name for name in (
        "ios-tests-final.log", "ios-visual-build-final.log", "ios-release.log",
        "android-validation-final.log", "core-tests-fresh.log", "manual-focus-final.log",
    )]
    if not list((evidence / "manual").glob("*.png")):
        raise SystemExit("Missing manual input evidence")
    if output.exists():
        raise SystemExit(f"Not overwriting evidence archive: {output}")
    output.parent.mkdir(parents=True, exist_ok=True)
    manifest = {"version": name, "build": build, "paired_states": len(expected),
                "original_screenshots": len(expected) * 2,
                "light_states": len(matrix["light"]), "dark_states": len(matrix["dark"]),
                "reference": "iOS left, Android right in comparison images",
                "limits": f"Simulator fixture review, not a zero-pixel/all-device guarantee; see VERIFICATION_{name}.md",
                "files": []}
    with zipfile.ZipFile(output, "x", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in paths:
            data = path.read_bytes()
            relative = path.relative_to(evidence).as_posix()
            safe_name = relative.replace(":", "-")
            if relative.startswith(("ios/", "android/")):
                if data[:8] != b"\x89PNG\r\n\x1a\n" or struct.unpack(">II", data[16:24]) != (matrix["width"], matrix["height"]):
                    raise SystemExit(f"Invalid original screenshot: {relative}")
            archive.writestr(safe_name, data)
            manifest["files"].append({"path": safe_name, "capture_path": relative, "sha256": hashlib.sha256(data).hexdigest()})
        for relative in [f"docs/VERIFICATION_{name}.md", f"docs/RELEASE_NOTES_{name}.md", "Scripts/fixtures/workflow-1.6.json"]:
            archive.write(ROOT / relative, Path(relative).name)
        archive.writestr("EVIDENCE_MANIFEST.json", json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    with zipfile.ZipFile(output) as archive:
        if archive.testzip():
            raise SystemExit("Evidence ZIP CRC failure")
    print(f"Packaged {len(expected)} paired states and manual input evidence: {output}")

if __name__ == "__main__":
    main()
