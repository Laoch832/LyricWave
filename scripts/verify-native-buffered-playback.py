#!/usr/bin/env python3
"""Check the exact official APK's PCM/recovery descriptors without modifying it or requesting licenses."""
import argparse
import hashlib
import importlib.util
import json
import zipfile
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('asset_contract', ROOT/'scripts/verify-native-asset-preload.py')
contract = importlib.util.module_from_spec(spec); spec.loader.exec_module(contract)
verify = contract.verify

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    args = parser.parse_args()
    profile = json.loads((ROOT/'host-applemusic/src/main/resources/host-profiles/7.0.0-1614.json').read_text())
    expected = profile['bufferedDrmRecovery']
    if hashlib.sha256(args.apk.read_bytes()).hexdigest() != expected['verification']['apkSha256']:
        raise SystemExit('Unverified host APK checksum')
    classes = {}
    with zipfile.ZipFile(args.apk) as apk:
        identity = contract.host._manifest_module.manifest_identity(apk.read('AndroidManifest.xml'))
        if identity != (profile['packageName'], profile['versionName'], profile['versionCode']):
            raise SystemExit('Unverified exact host identity')
        for name in apk.namelist():
            if name.endswith('.dex'): classes.update(contract.host.dex_classes(apk.read(name)))
    errors = verify(classes, expected)
    if errors: raise SystemExit('\n'.join(errors))
    print('PASS: official 7.0.0/1614 buffered playback descriptors and access flags; physical audio unverified')
if __name__ == '__main__': main()
