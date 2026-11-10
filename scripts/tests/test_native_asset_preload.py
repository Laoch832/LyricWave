import copy
import importlib.util
import json
import unittest
from pathlib import Path
ROOT=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('preload',ROOT/'scripts/verify-native-asset-preload.py')
preload=importlib.util.module_from_spec(spec);spec.loader.exec_module(preload)

class NativeAssetContractTest(unittest.TestCase):
    def setUp(self):
        self.contract=json.loads((ROOT/'host-applemusic/src/main/resources/host-profiles/7.0.0-1614.json').read_text())['nextAssetPreload']
        self.classes={name:{'method_access':dict(c['methods']),'fields':{f:v['descriptor'] for f,v in c['fields'].items()},'field_access':{f:v['access'] for f,v in c['fields'].items()}} for name,c in self.contract['verification']['classes'].items()}
    def test_missing_changed_signature_and_access_fail_closed(self):
        self.assertEqual([],preload.verify(self.classes,self.contract))
        missing=copy.deepcopy(self.classes);missing.pop(next(iter(missing)))
        self.assertTrue(preload.verify(missing,self.contract))
        changed=copy.deepcopy(self.classes);owner=next(n for n in changed if 'PlaybackAssetRequestManager;' in n)
        changed[owner]['method_access']['requestAsset(Lcom/apple/android/music/playback/model/PlayerMediaItem;Lcom/apple/android/music/playback/player/mediasource/AssetRequestCallback;JZ)V']=9
        self.assertTrue(preload.verify(changed,self.contract))
        changed=copy.deepcopy(self.classes)
        method=next(m for m in changed[owner]['method_access'] if m.startswith('requestAsset('))
        changed[owner]['method_access'][method.replace(';JZ)', ';IZ)')]=changed[owner]['method_access'].pop(method)
        self.assertTrue(preload.verify(changed,self.contract))
        changed=copy.deepcopy(self.classes);changed[owner]['field_access']['callbacksByMediaItem']=1
        self.assertTrue(preload.verify(changed,self.contract))
    def test_only_exact_1614_has_experimental_contract(self):
        paths=list((ROOT/'host-applemusic/src/main/resources/host-profiles').glob('*.json'))
        enabled=[p.name for p in paths if 'nextAssetPreload' in json.loads(p.read_text())]
        self.assertEqual(['7.0.0-1614.json'],enabled)
        self.assertEqual(1,self.contract['contractVersion'])
        self.assertFalse(self.contract['verification']['deviceValidated'])
        self.assertEqual(64,len(self.contract['verification']['apkSha256']))
if __name__=='__main__': unittest.main()
