"""Package the final paired screenshot matrix, not provisional captures."""
import hashlib
import json
from pathlib import Path
import struct
import zipfile

ROOT = Path(__file__).resolve().parents[1]
EVIDENCE = ROOT / "build/1.5.1-qa"
OUTPUT = ROOT / "dist/Source/Moji-v1.5.1-build41-visual-qa.zip"

def main():
    originals = sorted((EVIDENCE / "ios").glob("*.png"))
    names = {p.name for p in originals}
    if len(names) != 59 or sum(n.endswith("-light.png") for n in names) != 45:
        raise SystemExit("Expected exactly 45 light and 14 dark final iOS captures")
    if names != {p.name for p in (EVIDENCE / "android").glob("*.png")}:
        raise SystemExit("Android/iOS final screenshot matrix differs")
    paths = originals + sorted((EVIDENCE / "android").glob("*.png"))
    paths += sorted((EVIDENCE / "comparisons").glob("*.png"))
    if len(list((EVIDENCE / "comparisons").glob("sheet-*.png"))) != 16:
        raise SystemExit("Expected 12 light and 4 dark contact sheets")
    for name in ["android-inline-before.xml", "android-inline-keyboard.xml", "android-inline-keyboard.png", "android-inline-ready.xml", "android-inline-saved.xml", "android-inline-saved.png", "android-inline-relaunch.xml", "android-inline-relaunch.png"]:
        paths.append(EVIDENCE / "manual" / name)
    if OUTPUT.exists():
        raise SystemExit(f"Not overwriting evidence archive: {OUTPUT}")
    OUTPUT.parent.mkdir(parents=True, exist_ok=True)
    manifest = {"version": "1.5.1", "build": 41, "paired_states": 59,
                "original_screenshots": 118, "light_states": 45, "dark_states": 14,
                "reference": "iOS left, Android right in comparison images",
                "limits": "Simulator fixture review, not a zero-pixel/all-device guarantee; see VERIFICATION_1.5.1.md",
                "files": []}
    with zipfile.ZipFile(OUTPUT, "x", compression=zipfile.ZIP_DEFLATED) as archive:
        for path in paths:
            data = path.read_bytes()
            relative = path.relative_to(EVIDENCE).as_posix()
            # Avoid colon-containing filenames on Windows.
            name = relative.replace(":", "-")
            if relative.startswith(("ios/", "android/")):
                if data[:8] != b"\x89PNG\r\n\x1a\n" or struct.unpack(">II", data[16:24]) != (1179, 2556):
                    raise SystemExit(f"Invalid original screenshot: {relative}")
            archive.writestr(name, data)
            manifest["files"].append({"path": name, "capture_path": relative,
                                      "sha256": hashlib.sha256(data).hexdigest()})
        for relative in ["docs/VERIFICATION_1.5.1.md", "docs/RELEASE_NOTES_1.5.1.md", "Scripts/fixtures/workflow-1.5.json"]:
            archive.write(ROOT / relative, Path(relative).name)
        archive.writestr("EVIDENCE_MANIFEST.json", json.dumps(manifest, ensure_ascii=False, indent=2) + "\n")
    with zipfile.ZipFile(OUTPUT) as archive:
        if archive.testzip():
            raise SystemExit("Evidence ZIP CRC failure")
    print(f"Packaged 59 paired states and manual input evidence: {OUTPUT}")

if __name__ == "__main__":
    main()
