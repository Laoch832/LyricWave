#!/usr/bin/env python3
"""Verify the experimental next-asset contract against an exact official host APK (read only)."""
import argparse
import importlib.util
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

def main():
    p = argparse.ArgumentParser(description=__doc__); p.add_argument('apk',type=Path); args=p.parse_args()
    profile=json.loads((ROOT/'host-applemusic/src/main/resources/host-profiles/7.0.0-1614.json').read_text())
    with zipfile.ZipFile(args.apk) as apk:
        identity=host._manifest_module.manifest_identity(apk.read('AndroidManifest.xml'))
        if identity != (profile['packageName'],profile['versionName'],profile['versionCode']): raise SystemExit('Unverified exact host identity')
        classes={}
        for name in apk.namelist():
            if name.endswith('.dex'): classes.update(host.dex_classes(apk.read(name)))
    errors=verify(classes,profile['nextAssetPreload'])
    if errors: raise SystemExit('\n'.join(errors))
    print('PASS: exact 7.0.0/1614 experimental native asset descriptors; physical-device playback remains unverified')
if __name__=='__main__': main()
