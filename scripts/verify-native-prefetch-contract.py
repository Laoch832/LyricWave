"""Read-only candidate proof. Passing this inventory does not enable native preload or certify DRM behavior."""
import argparse
import importlib.util
import json
import zipfile
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location("host_profile_verifier", ROOT / "scripts/verify-host-profile.py")
host = importlib.util.module_from_spec(spec)
spec.loader.exec_module(host)

def verify(classes, candidate):
    errors = []
    if candidate.get("status") != "candidate-not-enabled":
        errors.append("Candidate must not claim runtime support")
    for name, contract in candidate["classes"].items():
        actual = classes.get(name)
        if actual is None:
            errors.append(f"Missing class {name}")
            continue
        for method, access in contract["methods"].items():
            if actual["method_access"].get(method) != access:
                errors.append(f"Method descriptor/access mismatch: {name}.{method}")
        for field, expected in contract["fields"].items():
            if actual["fields"].get(field) != expected["descriptor"] or actual["field_access"].get(field) != expected["access"]:
                errors.append(f"Field descriptor/access mismatch: {name}.{field}")
    return errors

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("apk", type=Path)
    args = parser.parse_args()
    candidate = json.loads((ROOT / "docs/experiments/native-prefetch-1614.json").read_text())
    with zipfile.ZipFile(args.apk) as apk:
        identity = host._manifest_module.manifest_identity(apk.read("AndroidManifest.xml"))
        if identity != (candidate["packageName"], candidate["versionName"], candidate["versionCode"]):
            raise SystemExit("Unverified host identity; candidate is exact-version only")
        classes = {}
        for name in apk.namelist():
            if name.endswith(".dex"):
                classes.update(host.dex_classes(apk.read(name)))
    errors = verify(classes, candidate)
    if errors:
        raise SystemExit("\n".join(errors))
    print("PASS: candidate descriptors/access match 7.0.0/1614; no runtime support or DRM prefetch claim")

if __name__ == "__main__":
    main()
