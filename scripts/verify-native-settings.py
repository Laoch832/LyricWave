#!/usr/bin/env python3
"""Verify one explicitly attested settings variant against the exact official APK (read only)."""
import argparse
import hashlib
import importlib.util
import json
import zipfile
from pathlib import Path
ROOT = Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('asset_contract', ROOT/'scripts/verify-native-asset-preload.py')
asset = importlib.util.module_from_spec(spec); spec.loader.exec_module(asset)
def descriptor(name):
    return {'void':'V','boolean':'Z','int':'I','float':'F','long':'J'}.get(name) or 'L'+name.replace('.','/')+';'
def verify(classes, variant):
    errors = asset.verify(classes, variant)
    def method(owner, name, parameters, returns):
        signature = name+'('+''.join(map(descriptor, parameters))+')'+descriptor(returns)
        access = classes.get(descriptor(owner), {}).get('method_access', {}).get(signature)
        if access is None: errors.append('Missing settings descriptor: '+owner+'.'+signature)
        return access
    for row in variant['members']['methods'].values():
        access=method(row['owner'],row['name'],row['parameters'],row['returns'])
        if access is not None and bool(access&8)!=row['static']: errors.append('Wrong invocation kind: '+row['name'])
    method(variant['fragmentClass'],variant['viewModelMethod'],[],variant['viewModelClass'])
    method(variant['categoryClass'],'<init>',['java.lang.String','java.util.List','java.lang.CharSequence','int'],'void')
    method(variant['actionClass'],'<init>',['java.lang.String','java.lang.String','boolean','java.lang.String',variant['artworkClass'],variant['callbackClass'],'int'],'void')
    method(variant['artworkClass'],'<init>',['java.lang.String','float','boolean'],'void')
    method(variant['callbackClass'],'invoke',[],'java.lang.Object')
    for owner,field,kind in [(variant['categoryClass'],variant['categoryItemsField'],'java.util.List'),(variant['actionClass'],variant['actionCallbackField'],variant['callbackClass']),(variant['unitClass'],variant['unitField'],variant['unitClass'])]:
        if classes.get(descriptor(owner),{}).get('fields',{}).get(field)!=descriptor(kind):errors.append('Wrong settings model field: '+owner+'.'+field)
    return errors

def main():
    parser=argparse.ArgumentParser(description=__doc__);parser.add_argument('apk',type=Path);args=parser.parse_args()
    profile=json.loads((ROOT/'host-applemusic/src/main/resources/host-profiles/7.0.0-1614.json').read_text())
    digest=hashlib.sha256(args.apk.read_bytes()).hexdigest()
    variants=[v for v in profile.get('settingsVariants',[]) if v['verification']['apkSha256']==digest]
    if len(variants)!=1:raise SystemExit('Unverified settings APK digest')
    with zipfile.ZipFile(args.apk) as apk:
        identity=asset.host._manifest_module.manifest_identity(apk.read('AndroidManifest.xml'))
        if identity!=(profile['packageName'],profile['versionName'],profile['versionCode']):raise SystemExit('Unverified exact host identity')
        classes={}
        for name in apk.namelist():
            if name.endswith('.dex'):classes.update(asset.host.dex_classes(apk.read(name)))
    errors=verify(classes,variants[0])
    if errors:raise SystemExit('\n'.join(errors))
    print('PASS: exact native settings methods, model constructors and fields; device flow remains unverified')
if __name__=='__main__':main()
