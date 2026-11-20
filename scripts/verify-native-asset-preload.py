#!/usr/bin/env python3
"""Verify the next-asset contract against an exact official host APK (read only)."""
import argparse
import importlib.util
import hashlib
import json
import zipfile
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('host', ROOT/'scripts/verify-host-profile.py')
host = importlib.util.module_from_spec(spec); spec.loader.exec_module(host)

def verify(classes, contract):
    errors = []
    for name, expected in contract['verification']['classes'].items():
        actual = classes.get(name)
        if actual is None:
            errors.append(f'Missing class: {name}'); continue
        for method, access in expected['methods'].items():
            if actual['method_access'].get(method) != access: errors.append(f'Method mismatch: {name}.{method}')
        for field, details in expected['fields'].items():
            if actual['fields'].get(field) != details['descriptor'] or actual['field_access'].get(field) != details['access']:
                errors.append(f'Field mismatch: {name}.{field}')
    return errors

def verify_apk(path, section):
    with zipfile.ZipFile(path) as apk:
        package, name, code = host._manifest_module.manifest_identity(apk.read('AndroidManifest.xml'))
        profiles = ROOT / 'host-applemusic/src/main/resources/host-profiles'
        profile_path = profiles / f'{name}-{code}.json'
        index = json.loads((profiles / 'index.json').read_text())
        if package != 'com.apple.android.music' or profile_path.name not in index['profiles']:
            raise SystemExit('No exact Apple Music profile')
        profile = json.loads(profile_path.read_text())
        expected = profile.get(section)
        if expected is None:
            raise SystemExit(f'No verified {section} contract for {name}/{code}')
        with path.open('rb') as content:
            if hashlib.file_digest(content, 'sha256').hexdigest() != expected['verification']['apkSha256']:
                raise SystemExit('Unverified host APK checksum')
        classes = {}
        for entry in apk.namelist():
            if entry.endswith('.dex'):
                classes.update(host.dex_classes(apk.read(entry)))
    errors = verify(classes, expected)
    if errors:
        raise SystemExit('\n'.join(errors))
    print(f'PASS: {name}/{code} {section} descriptors and access flags')


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('apk', type=Path)
    verify_apk(parser.parse_args().apk, 'nextAssetPreload')


if __name__ == '__main__':
    main()
